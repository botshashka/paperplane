package dev.paperplane.cli.server

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap

/**
 * Recursive YAML deep-merge with "override wins" semantics for use by [PaperServerManager] when
 * writing `paper-global.yml` and `paper-world-defaults.yml`. The user can specify a sparse subtree
 * in `paperplane.yml` that's layered on top of PaperPlane's defaults without having to replicate
 * Paper's full schema.
 *
 * Merge rules:
 * - Two maps: recursively merge keys. Keys present only in one side are kept as-is. Keys present
 *   in both are merged by the rules below.
 * - Scalars / nulls / tagged nodes: [override] wins.
 * - Lists: [override] wins (no element-wise merge — lists in paper configs are usually replaced
 *   wholesale, e.g. `enabled-packs`, so merging element-by-element would surprise users).
 */
internal object YamlDeepMerge {
  /**
   * Merges [overrideYaml] on top of [baseYaml], both as raw YAML strings, and returns the merged
   * YAML. Called once per file at configure time.
   */
  fun merge(baseYaml: String, override: YamlMap?): String {
    if (override == null) return baseYaml
    val yaml = Yaml.default
    val baseNode = yaml.parseToYamlNode(baseYaml)
    val merged = mergeNodes(baseNode, override)
    return yaml.encodeToString(YamlNode.serializer(), merged)
  }

  private fun mergeNodes(base: YamlNode, override: YamlNode): YamlNode {
    if (base is YamlMap && override is YamlMap) return mergeMaps(base, override)
    // Any other combination: override wins wholesale.
    return override
  }

  private fun mergeMaps(base: YamlMap, override: YamlMap): YamlMap {
    val result = LinkedHashMap<YamlScalar, YamlNode>()
    // YamlScalar equality isn't content-based, so index base keys by their string content for
    // O(1) override lookup instead of O(n) scanning.
    val byContent = LinkedHashMap<String, YamlScalar>()
    for ((k, v) in base.entries) {
      result[k] = v
      byContent[k.content] = k
    }
    for ((overrideKey, overrideValue) in override.entries) {
      val matchingBaseKey = byContent[overrideKey.content]
      if (matchingBaseKey == null) {
        result[overrideKey] = overrideValue
      } else {
        result[matchingBaseKey] = mergeNodes(result[matchingBaseKey]!!, overrideValue)
      }
    }
    return YamlMap(result, base.path)
  }
}

/** Tiny helper used in tests. */
internal fun yamlMap(yaml: String): YamlMap = Yaml.default.parseToYamlNode(yaml).yamlMap
