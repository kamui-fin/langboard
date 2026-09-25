#!/usr/bin/env python3
"""Rewrites Tencent's low-bit Hy-MT2 GGUFs into tensor types that stock llama.cpp runs.

Tencent's 2-bit and 1.25-bit files were written by forks of llama.cpp whose tensor type ids clash
with mainline: their type 40 is Q2_0C (ggml-org/llama.cpp PR #19357) where mainline has NVFP4, and
their type 42 is STQ1_0 "Sherry" (PR #22836) where mainline has Q2_0. Mainline refuses both files.

Both formats map exactly onto mainline types, so the conversion is lossless (every weight
dequantizes to the same float):

  Q2_0C  -> Q2_K   value (2c - 3) * d  ==  (2d) * 1 * c - d * 3   (scale 1, min 3, dmin = d)
  STQ1_0 -> TQ2_0  ternary {-d, 0, +d}, one fp16 scale per 256 weights in both

The price is size: Q2_K is 2.625 bits per weight against 2.03, TQ2_0 is 2.06 against 1.31.
Every other tensor, all metadata and the tokenizer are copied unchanged.

    python3 model/convert_hymt_gguf.py Hy-MT2-1.8B-2Bit.gguf hymt2-1.8b-2bit-q2k.gguf

Each converted tensor is checked against the source by dequantizing both.
"""

import struct
import sys

import numpy as np

QK_K = 256

# Source ids as written by Tencent's forks.
SRC_Q2_0C = 40
SRC_STQ1_0 = 42
# Mainline ids (ggml/include/ggml.h).
Q2_K = 10
TQ2_0 = 35
# llama_ftype (include/llama.h), for general.file_type.
FTYPE = {Q2_K: 10, TQ2_0: 37}

# Block sizes in bytes for the types that appear in these files, and the ones we write.
TYPE_SIZE = {0: (1, 4), 1: (1, 2), 14: (256, 210), SRC_Q2_0C: (512, 130), SRC_STQ1_0: (256, 42),
             Q2_K: (256, 84), TQ2_0: (256, 66), 12: (256, 144), 10: (256, 84)}

# STQ1_0 codebook, index (sign << 4) | slot -> four 2-bit lanes (0 = -1, 1 = 0, 2 = +1), lane 0 lowest.
STQ1_0_CODEBOOK = np.array([
  0xA9, 0x89, 0x29, 0x09, 0xA6, 0x86, 0x26, 0x06, 0x9A, 0x92, 0x1A, 0x12, 0x6A, 0x62, 0x4A, 0x42,
  0x01, 0x21, 0x81, 0xA1, 0x04, 0x24, 0x84, 0xA4, 0x10, 0x18, 0x90, 0x98, 0x40, 0x48, 0x60, 0x68,
], dtype=np.uint8)


class Reader:
  def __init__(self, data):
    self.d, self.o = data, 0

  def take(self, fmt):
    v = struct.unpack_from(fmt, self.d, self.o)
    self.o += struct.calcsize(fmt)
    return v[0]

  def string(self):
    n = self.take('<Q')
    s = bytes(self.d[self.o:self.o + n]).decode()
    self.o += n
    return s

  def skip_value(self, t):
    sizes = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
    if t in sizes:
      self.o += sizes[t]
    elif t == 8:
      self.string()
    elif t == 9:
      at, n = self.take('<I'), self.take('<Q')
      for _ in range(n):
        self.skip_value(at)
    else:
      raise ValueError(f'unknown GGUF value type {t}')


def nbytes(t, n):
  blk, size = TYPE_SIZE[t]
  assert n % blk == 0, (t, n)
  return n // blk * size


# ---- Q2_0C -> Q2_K

def q2_0c_codes(raw):
  """(blocks, d fp16 bits, codes 0..3 per weight in order) for Q2_0C data."""
  b = raw.reshape(-1, 130)
  d = b[:, :2].copy().view(np.float16).reshape(-1)
  qs = b[:, 2:]
  codes = np.stack([(qs >> s) & 3 for s in (0, 2, 4, 6)], axis=-1).reshape(-1, 512)
  return d, codes


def q2_0c_to_q2_k(raw):
  d, codes = q2_0c_codes(raw)
  codes = codes.reshape(-1, 2, 2, 4, 32)            # 512-block, super-block, 128-chunk, shift j, lane l
  qs = (codes[..., 0, :] | codes[..., 1, :] << 2 | codes[..., 2, :] << 4 | codes[..., 3, :] << 6)
  qs = qs.reshape(-1, 2, 64).astype(np.uint8)       # per super-block: qs[chunk*32 + l]
  n = qs.shape[0]
  out = np.empty((n, 2, 84), dtype=np.uint8)
  out[:, :, 0:16] = 0x31                            # scale 1 (low nibble), min 3 (high nibble)
  out[:, :, 16:80] = qs
  d2 = (d.astype(np.float32) * 2).astype(np.float16)
  assert np.all(d2.astype(np.float32) == d.astype(np.float32) * 2), '2d not exact in fp16'
  out[:, :, 80:82] = np.repeat(d2.view(np.uint8).reshape(-1, 1, 2), 2, axis=1)
  out[:, :, 82:84] = np.repeat(d.view(np.uint8).reshape(-1, 1, 2), 2, axis=1)
  return out.reshape(-1)


def dequant_q2_0c(raw):
  d, codes = q2_0c_codes(raw)
  return ((2 * codes.astype(np.float32) - 3) * d.astype(np.float32)[:, None]).reshape(-1)


def dequant_q2_k(raw):
  """Straight port of ggml's dequantize_row_q2_K."""
  b = raw.reshape(-1, 84)
  sc = b[:, 0:16]
  qs = b[:, 16:80].reshape(-1, 2, 32)
  d = b[:, 80:82].copy().view(np.float16).astype(np.float32)
  dmin = b[:, 82:84].copy().view(np.float16).astype(np.float32)
  q = np.stack([(qs >> s) & 3 for s in (0, 2, 4, 6)], axis=2)       # block, chunk, j, l(32)
  q = q.reshape(-1, 16, 16).astype(np.float32)                      # sub-block index = chunk*8 + j*2 + l//16
  dl = d * (sc & 0xF).astype(np.float32)
  ml = dmin * (sc >> 4).astype(np.float32)
  return (dl[:, :, None] * q - ml[:, :, None]).reshape(-1)


# ---- STQ1_0 -> TQ2_0

def stq1_0_lanes(raw):
  """(d, per-weight 2-bit lane values 0/1/2 in weight order) for STQ1_0 data."""
  b = raw.reshape(-1, 42)
  qs, sign = b[:, 0:32], b[:, 32:40]
  d = b[:, 40:42].copy().view(np.float16).reshape(-1)
  g = np.arange(64)
  code = (qs[:, g // 2] >> (4 * (g & 1))) & 0xF
  sgn = (sign[:, g // 8] >> (g % 8)) & 1
  qpack = STQ1_0_CODEBOOK[(sgn.astype(np.int32) << 4) | code]      # block, group
  lanes = np.stack([(qpack >> (2 * p)) & 3 for p in range(4)], axis=-1)  # block, group, p
  # Group g covers weights chunk*64 + gloc + p*16 (stride-16 within each 64-weight chunk).
  w = np.empty((b.shape[0], 256), dtype=np.uint8)
  chunk, gloc = g // 16, g % 16
  for p in range(4):
    w[:, chunk * 64 + gloc + p * 16] = lanes[:, :, p]
  return d, w


def stq1_0_to_tq2_0(raw):
  d, w = stq1_0_lanes(raw)
  assert w.max() <= 2
  q = w.reshape(-1, 2, 4, 32)                        # half j, shift l, m: weight j*128 + l*32 + m
  qs = (q[:, :, 0] | q[:, :, 1] << 2 | q[:, :, 2] << 4 | q[:, :, 3] << 6).reshape(-1, 64).astype(np.uint8)
  out = np.empty((qs.shape[0], 66), dtype=np.uint8)
  out[:, :64] = qs
  out[:, 64:66] = d.view(np.uint8).reshape(-1, 2)
  return out.reshape(-1)


def dequant_stq1_0(raw):
  d, w = stq1_0_lanes(raw)
  return ((w.astype(np.float32) - 1) * d.astype(np.float32)[:, None]).reshape(-1)


def dequant_tq2_0(raw):
  """Straight port of ggml's dequantize_row_tq2_0."""
  b = raw.reshape(-1, 66)
  qs = b[:, :64].reshape(-1, 2, 32)
  d = b[:, 64:66].copy().view(np.float16).astype(np.float32)
  q = np.stack([(qs >> (2 * l)) & 3 for l in range(4)], axis=2).reshape(-1, 256)
  return ((q.astype(np.float32) - 1) * d).reshape(-1)


CONVERT = {
  SRC_Q2_0C: (Q2_K, q2_0c_to_q2_k, dequant_q2_0c, dequant_q2_k),
  SRC_STQ1_0: (TQ2_0, stq1_0_to_tq2_0, dequant_stq1_0, dequant_tq2_0),
}


def convert(src_path, dst_path):
  data = np.memmap(src_path, dtype=np.uint8, mode='r')
  r = Reader(data)
  assert bytes(data[:4]) == b'GGUF'
  r.o = 4
  version, n_tensors, n_kv = r.take('<I'), r.take('<Q'), r.take('<Q')
  kv_start = r.o
  alignment = 32
  file_type_at = None
  for _ in range(n_kv):
    key = r.string()
    t = r.take('<I')
    if key == 'general.alignment':
      alignment = struct.unpack_from('<I', data, r.o)[0]
    if key == 'general.file_type':
      assert t == 4
      file_type_at = r.o - kv_start
    r.skip_value(t)
  kv = bytearray(data[kv_start:r.o])

  tensors = []
  for _ in range(n_tensors):
    name = r.string()
    dims = [r.take('<Q') for _ in range(r.take('<I'))]
    tensors.append((name, dims, r.take('<I'), r.take('<Q')))
  data_start = -(-r.o // alignment) * alignment

  out_types = [CONVERT[t][0] if t in CONVERT else t for _, _, t, _ in tensors]
  converted = {t for _, _, t, _ in tensors if t in CONVERT}
  if not converted:
    sys.exit('nothing to convert: no Q2_0C (40) or STQ1_0 (42) tensors')
  if file_type_at is not None:
    struct.pack_into('<I', kv, file_type_at, FTYPE[CONVERT[converted.pop()][0]])

  def aligned(n):
    return -(-n // alignment) * alignment

  offsets, off = [], 0
  for (_, dims, _, _), t in zip(tensors, out_types):
    offsets.append(off)
    off = aligned(off + nbytes(t, int(np.prod(dims))))

  header = bytearray(b'GGUF' + struct.pack('<IQQ', version, n_tensors, n_kv) + kv)
  for (name, dims, _, _), t, o in zip(tensors, out_types, offsets):
    nb = name.encode()
    header += struct.pack('<Q', len(nb)) + nb + struct.pack('<I', len(dims))
    header += b''.join(struct.pack('<Q', x) for x in dims) + struct.pack('<IQ', t, o)
  header += b'\0' * (aligned(len(header)) - len(header))

  with open(dst_path, 'wb') as f:
    f.write(header)
    for (name, dims, t, src_off), out_t, o in zip(tensors, out_types, offsets):
      n = int(np.prod(dims))
      raw = np.asarray(data[data_start + src_off:data_start + src_off + nbytes(t, n)])
      if t in CONVERT:
        _, conv, deq_src, deq_dst = CONVERT[t]
        blob = conv(raw)
        if not np.array_equal(deq_src(raw), deq_dst(blob)):
          sys.exit(f'{name}: converted weights differ from the source')
      else:
        blob = raw
      assert f.tell() == len(header) + o
      f.write(blob.tobytes())
      f.write(b'\0' * (aligned(f.tell() - len(header)) - (f.tell() - len(header))))
  print(f'{dst_path}: {len(tensors)} tensors, '
        f'{sum(t in CONVERT for _, _, t, _ in tensors)} converted and verified')


if __name__ == '__main__':
  if len(sys.argv) != 3:
    sys.exit(__doc__)
  convert(sys.argv[1], sys.argv[2])
