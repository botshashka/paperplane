package dev.paperplane.gradle

import javax.inject.Inject
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

abstract class PaperPlaneExtension @Inject constructor(project: Project) {
  abstract val mainClass: Property<String>
  abstract val apiVersion: Property<String>
  abstract val pluginName: Property<String>
  abstract val description: Property<String>
  abstract val authors: ListProperty<String>
  abstract val website: Property<String>
  abstract val depend: ListProperty<String>
  abstract val softDepend: ListProperty<String>

  val commands: NamedDomainObjectContainer<CommandDefinition> =
      project.objects.domainObjectContainer(CommandDefinition::class.java) { name ->
        project.objects.newInstance(CommandDefinition::class.java, name)
      }

  val permissions: NamedDomainObjectContainer<PermissionDefinition> =
      project.objects.domainObjectContainer(PermissionDefinition::class.java) { name ->
        project.objects.newInstance(PermissionDefinition::class.java, name)
      }

  init {
    pluginName.convention(project.name)
  }
}

abstract class CommandDefinition @Inject constructor(val name: String) {
  abstract val description: Property<String>
  abstract val usage: Property<String>
  abstract val aliases: ListProperty<String>
  abstract val permission: Property<String>
}

abstract class PermissionDefinition @Inject constructor(val name: String) {
  abstract val default: Property<String>
  abstract val description: Property<String>
  abstract val children: MapProperty<String, Boolean>
}
