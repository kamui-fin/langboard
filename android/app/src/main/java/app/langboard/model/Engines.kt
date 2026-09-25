package app.langboard.model

import app.langboard.core.ModelCheckEngine
import app.langboard.core.ModelExplainEngine
import app.langboard.core.ModelFillGapEngine

/** The model-backed engines, shared by the keyboard, Try it and the selection menu. Call [ModelManager.init] first. */
object Engines {
  val fill = ModelFillGapEngine(ModelManager, ModelManager.spec.installedName, ModelManager::prompts)
  val explain = ModelExplainEngine(ModelManager, ModelManager::prompts)
  val check = ModelCheckEngine(ModelManager, ModelManager::prompts)
}
