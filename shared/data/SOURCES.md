# Data sources: what the research cited, and what else is out there

*Checked 25 Sep 2026 against the live cards, READMEs and sample rows (handoff §17, workstream C). Not legal advice. `data_manifest.csv` is the row-level record.*

**Bottom line.** No public dataset has what Langboard needs most: authentic, commercially usable,
contemporary Mainland casual messages with native targets. The cited sources are method references,
not training data. The two most valuable finds are **YACLC** (learner Chinese with native *fluency*
rewrites and acceptability votes, which is Naturalize + UNCHANGED almost exactly; research-only,
commercial license for purchase) and **Tatoeba** (commercially usable, but only its English side is
reliably native). The pilot's human targets still have to be written or commissioned by native speakers.

## Cited in the research

| Source | License (as published) | What it actually is | Use for Langboard |
| --- | --- | --- | --- |
| [XiangJinYu Humanize Dataset](https://huggingface.co/datasets/XiangJinYu/Qwen3.5-9B-Humanize-Dataset) | CC BY-NC 4.0 | 18k SFT + 7.5k DPO rows. **Chinese academic abstracts** (CSL): input = AI rewrite, target = the human original. | **Method only.** It is our inverse-synthetic recipe (§6) at scale. Its DPO stage used both over-formal *and* over-casual rejects, which is the handoff's §16 "rejected type B". The domain (academic prose) is useless for chat. Not for training. |
| [genz-to-english](https://huggingface.co/datasets/Sankar-2910/genz-to-english) | Apache-2.0 on the card | 300k slang → "standard English" pairs. Rows read LLM-generated, and targets are flattened assistant English ("He performed very poorly in chemistry"). | **Meaning reference** for English slang in EN→ZH Fill (ghosted, cooked, lowkey) and for mining slang families for eval cases. Never a naturalness target. Provenance unverified. |
| [FCGEC](https://github.com/xlxwalex/FCGEC) | Repo Apache-2.0; the README states no data restriction (the handoff says research-only: unconfirmed, check the data folder/paper before any use) | 41k native-speaker sentences from school-exam 病句 and news, 1.7 references each. | **Low relevance.** Formal/exam errors, not messaging. Skip. |
| [YACLC](https://github.com/blcuicall/YACLC) | **Non-commercial research only; commercial use by purchase** (README) | 10k learner sentences. 10 annotators each give an acceptability vote, minimal grammatical corrections, **and fluency rewrites**. | **Most relevant cited source.** Learner input → native fluent rewrite is ZH Naturalize; the acceptability votes are an UNCHANGED signal. Use now as a reference for designing eval cases and error taxonomy; **ask BLCU for a commercial license** if we want it in training. |
| [MuCGEC](https://github.com/HillZhang1999/MuCGEC) | Repo Apache-2.0; data sampled from NLPCC18 / CGED / Lang-8 (learner corpora with research terms) | 7k learner sentences, multi-reference, plus annotation guidelines and the **ChERRANT** scorer. | **ChERRANT (code, Apache) is directly useful**: character-level edit precision/recall for Naturalize minimality. The data and guidelines are references. |
| [LessThanThree Humanlike Chat](https://huggingface.co/LessThanThreeAI/Qwen3.8-27B-Humanlike-Chat-GGUF) (model) | Apache-2.0 weights | Trained on 139k *real private* messages (next-reply only, loss on the reply). | Evidence that authentic human targets work; data is private. Nothing to reuse. |
| [humanizer-gemma](https://huggingface.co/jialinyyzz/humanizer-gemma-4-e4b) (model) | Gemma | SFT + DPO + GRPO; training data unreleased ("mixed licences"). [GitHub](https://github.com/sgaofen/humanizer) has the eval set, judge prompts and training scripts. | **Method reference** for our teacher/critic prompts and two-vote judging. |
| [XiangJinYu SFT](https://huggingface.co/XiangJinYu/Qwen3.5-9B-Humanize-SFT) / [DPO-Round2](https://huggingface.co/911-copy/Qwen3.5-9B-Humanize-DPO-Round2) (models) | Apache-2.0 weights | Trained on the dataset above. | Nothing beyond the dataset. |

## Not in the research, worth knowing

| Source | License | What it is | Verdict |
| --- | --- | --- | --- |
| [Tatoeba cmn–eng](https://tatoeba.org) ([Anki export](https://www.manythings.org/anki/)) | **CC BY 2.0 FR: commercial use allowed with attribution** | 32k human sentence pairs; ~20k short Simplified-looking. The Chinese side is often *translated from English* (它有改善了, 我被他英语的快速进步惊呆了) and mixes Traditional. | **Usable, with filtering.** The English side is native and can seed ZH→EN Fill and EN Naturalize targets. The Chinese side needs native review before it is a target, or it teaches translationese. |
| [ASCEND](https://huggingface.co/datasets/CAiRE/ASCEND) | CC BY-SA 4.0 | Transcribed Mandarin–English code-switching conversations by HK university students; 37% of utterances switch. | **Reference for the Latin question**: which English words bilinguals keep in Chinese (schedule, deadline, PhD, teaching plan). Not Mainland messaging register; share-alike. |
| [LCCC](https://huggingface.co/datasets/thu-coai/lccc) | MIT on the repo, but the text is scraped Weibo | 12M casual Chinese dialogues. | **Research reference only** (handoff rule on scraped social media). |
| [KdConv](https://huggingface.co/datasets/thu-coai/kd_conv_with_kb) | Apache-2.0 | 4.5k crowdsourced Chinese dialogues about films/music/travel. | Commercially usable but stiff, knowledge-chat register. Low value. |
| [NaSGEC](https://github.com/HillZhang1999/NaSGEC) | None stated | Native errors from WeChat articles, theses and exams. | Reference only. |
| [JFLEG](https://huggingface.co/datasets/jhu-clsp/jfleg) | CC BY-NC-SA 4.0 | Learner English with 4 **fluency** rewrites each. | The English twin of YACLC: EN Naturalize eval design reference. Non-commercial. |
| DailyDialog, EmpatheticDialogues | CC BY-NC(-SA) | Crowdsourced English dialogues, textbook-ish. | Skip. |
| Synthetic-Persona-Chat | CC BY 4.0 | LLM-generated. | Skip: not human. |
| OpenSubtitles zh–en | Unknown (subtitle copyright) | Huge conversational parallel corpus. | Research reference only. |

## What this means for the pilot

1. **Human targets:** commission or write them (two native Mainland speakers, one native American
   English speaker), then filter Tatoeba's English side to fill the English slices. This is the
   critical path, and it can't be bought off the shelf.
2. **Ask BLCU about a YACLC commercial license.** It is the closest existing data to ZH Naturalize +
   UNCHANGED, with ten native judgments per sentence.
3. **Adopt ChERRANT** for Naturalize edit precision in `bakeoff.py` once the rewrites are rated.
4. **Borrow methods, not rows:** XiangJinYu's two-sided DPO rejects and humanizer's judge prompts,
   when §16 DPO is ever justified.
