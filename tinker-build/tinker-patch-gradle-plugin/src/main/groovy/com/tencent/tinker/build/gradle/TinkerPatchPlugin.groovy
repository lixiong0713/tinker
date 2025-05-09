/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.build.gradle

import com.android.build.gradle.api.ApkVariant
import com.tencent.tinker.build.gradle.extension.*
import com.tencent.tinker.build.gradle.task.*
import com.tencent.tinker.build.util.FileOperation
import com.tencent.tinker.build.util.TypedValue
import com.tencent.tinker.build.util.Utils
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import sun.misc.Unsafe

import java.lang.reflect.Field

/**
 * Registers the plugin's tasks.
 *
 * @author zhangshaowen
 */

class TinkerPatchPlugin implements Plugin<Project> {
    public static final String ISSUE_URL = "https://github.com/Tencent/tinker/issues"

    private Project mProject = null

    @Override
    public void apply(Project project) {
        mProject = project

        //osdetector change its plugin name in 1.4.0
        try {
            mProject.apply plugin: 'osdetector'
        } catch (Throwable e) {
            mProject.apply plugin: 'com.google.osdetector'
        }

        /**
         * 在项目扩展中创建一个名为 tinkerPatch 的扩展，类型为 TinkerPatchExtension
         * 对应于 build.gradle 中的 tinkerPatch { } 配置块
         */
        mProject.extensions.create('tinkerPatch', TinkerPatchExtension)

        // 在 tinkerPatch 扩展中创建一个名为 buildConfig 的子扩展，类型为 TinkerBuildConfigExtension，并传入项目对象
        mProject.tinkerPatch.extensions.create('buildConfig', TinkerBuildConfigExtension, mProject)
        // 在 tinkerPatch 扩展中创建名为 dex 的子扩展，类型为 TinkerDexExtension，并传入项目对象
        mProject.tinkerPatch.extensions.create('dex', TinkerDexExtension, mProject)
        // 在 tinkerPatch 扩展中创建名为 lib 的子扩展，类型为 TinkerLibExtension
        mProject.tinkerPatch.extensions.create('lib', TinkerLibExtension)
        // 在 tinkerPatch 扩展中创建名为 res 的子扩展，类型为 TinkerResourceExtension
        mProject.tinkerPatch.extensions.create('res', TinkerResourceExtension)
        // 在 tinkerPatch 扩展中创建名为 arkHot 的子扩展，类型为 TinkerArkHotExtension
        mProject.tinkerPatch.extensions.create("arkHot", TinkerArkHotExtension)
        // 在 tinkerPatch 扩展中创建名为 packageConfig 的子扩展，类型为 TinkerPackageConfigExtension，并传入项目对象
        mProject.tinkerPatch.extensions.create('packageConfig', TinkerPackageConfigExtension, mProject)
        // 在 tinkerPatch 扩展中创建名为 sevenZip 的子扩展，类型为 TinkerSevenZipExtension，并传入项目对象
        mProject.tinkerPatch.extensions.create('sevenZip', TinkerSevenZipExtension, mProject)

        // 检查项目是否应用了 com.android.application 插件
        if (!mProject.plugins.hasPlugin('com.android.application')) {
            // 如果未应用，抛出 Gradle 异常，提示需要 Android 应用插件
            throw new GradleException('generateTinkerApk: Android Application plugin required')
        }

        /**
         * 获取项目的 Android 扩展对象
         * 对应于 build.gradle 中的 android { } 配置块
         */
        def android = mProject.extensions.android

        try {
            //close preDexLibraries
            android.dexOptions.preDexLibraries = false

            //open jumboMode, 说明文档: [https://cloud.tencent.com/developer/article/1922305]
            android.dexOptions.jumboMode = true

            //disable dex archive mode
            disableArchiveDex()

            //禁止打了运行时注解的类全部打到主dex中
            android.dexOptions.keepRuntimeAnnotatedClasses = false
        } catch (Throwable e) {
            //no preDexLibraries field, just continue
        }

        // afterEvaluate 是 Gradle 中处理动态配置的关键工具，适用于需要在项目完全配置后执行的操作。
        mProject.afterEvaluate {
            // 获取 tinkerPatch 扩展对象
            def configuration = mProject.tinkerPatch

            if (!configuration.tinkerEnable) {
                mProject.logger.error("tinker tasks are disabled.")
                return
            }

            mProject.logger.error("----------------------tinker build warning ------------------------------------")
            mProject.logger.error("tinker auto operation: ")
            mProject.logger.error("excluding annotation processor and source template from app packaging. Enable dx jumboMode to reduce package size.")
            mProject.logger.error("enable dx jumboMode to reduce package size.")
            mProject.logger.error("disable preDexLibraries to prevent ClassDefNotFoundException when your app is booting.")
            mProject.logger.error("disable archive dex mode so far for keeping dex apply.")
            mProject.logger.error("")
            mProject.logger.error("tinker will change your build configs:")
            mProject.logger.error("we will add TINKER_ID=${configuration.buildConfig.tinkerId} in your build output manifest file ${project.buildDir}/intermediates/manifests/full/*")
            mProject.logger.error("")
            mProject.logger.error("if minifyEnabled is true")

            // 获取 buildConfig 中的 applyMapping 配置路径
            String tempMappingPath = configuration.buildConfig.applyMapping

            if (FileOperation.isLegalFile(tempMappingPath)) {
                // 如果是合法文件，记录将使用该映射文件构建 APK 的信息
                mProject.logger.error("we will build ${mProject.getName()} apk with apply mapping file ${tempMappingPath}")
            }

            mProject.logger.error("you will find the gen proguard rule file at ${TinkerBuildPath.getProguardConfigPath(project)}")
            mProject.logger.error("and we will help you to put it in the proguardFiles.")
            mProject.logger.error("")
            mProject.logger.error("if multiDexEnabled is true")
            mProject.logger.error("you will find the gen multiDexKeepProguard file at ${TinkerBuildPath.getMultidexConfigPath(project)}")
            mProject.logger.error("and we will help you to put it in the MultiDexKeepProguardFile.")
            mProject.logger.error("")
            mProject.logger.error("if applyResourceMapping file is exist")

            // 获取 buildConfig 中的 applyResourceMapping 配置路径
            String tempResourceMappingPath = configuration.buildConfig.applyResourceMapping
            if (FileOperation.isLegalFile(tempResourceMappingPath)) {
                mProject.logger.error("we will build ${mProject.getName()} apk with resource R.txt ${tempResourceMappingPath} file")
            } else {
                mProject.logger.error("we will build ${mProject.getName()} apk with resource R.txt file")
            }
            mProject.logger.error("if resources.arsc has changed, you should use applyResource mode to build the new apk!")
            mProject.logger.error("-----------------------------------------------------------------")

            // 遍历 Android 项目的所有应用变体
            android.applicationVariants.all { ApkVariant variant ->
                // 获取变体名称
                def variantName = variant.name
                // 将变体名称首字母大写
                def capitalizedVariantName = variantName.capitalize()

                // 获取该变体的 Instant Run 任务
                def instantRunTask = Compatibilities.getInstantRunTask(project, variant)
                if (instantRunTask != null) {
                    // 如果存在，抛出 Gradle 异常，提示 Tinker 不支持 Instant Run 模式
                    throw new GradleException(
                            "Tinker does not support instant run mode, please trigger build"
                                    + " by assemble${capitalizedVariantName} or disable instant run"
                                    + " in 'File->Settings...'."
                    )
                }

                // 创建一个名为 tinkerPatch${capitalizedVariantName} 的任务，类型为 TinkerPatchSchemaTask
                TinkerPatchSchemaTask tinkerPatchBuildTask = mProject.tasks.create("tinkerPatch${capitalizedVariantName}", TinkerPatchSchemaTask)
                // 将该变体的签名配置赋值给 tinkerPatchBuildTask 的 signConfig 属性
                tinkerPatchBuildTask.signConfig = variant.signingConfig

                // Create a task to add a build TINKER_ID to AndroidManifest.xml
                // This task must be called after "process${variantName}Manifest", since it
                // requires that an AndroidManifest.xml exists in `build/intermediates`.
                // 创建一个任务，用于在 AndroidManifest.xml 中添加 TINKER_ID
                // 此任务必须在 process${variantName}Manifest 任务之后执行，因为需要在 build/intermediates 中存在 AndroidManifest.xml 文件
                def agpProcessManifestTask = Compatibilities.getProcessManifestTask(project, variant)
                def tinkerManifestAction = new TinkerManifestAction(project)
                // 在 agpProcessManifestTask 任务执行完成后执行 tinkerManifestAction
                agpProcessManifestTask.doLast tinkerManifestAction

                // 遍历该变体的所有输出
                variant.outputs.each { variantOutput ->
                    // 调用 setPatchNewApkPath 方法，设置补丁新 APK 的路径
                    setPatchNewApkPath(configuration, variantOutput, variant, tinkerPatchBuildTask)
                    // 调用 setPatchOutputFolder 方法，设置补丁输出文件夹
                    setPatchOutputFolder(configuration, variantOutput, variant, tinkerPatchBuildTask)

                    // 获取输出文件夹名称
                    def outputName = variantOutput.dirName
                    // 如果以 / 结尾，去掉最后一个字符
                    if (outputName.endsWith("/")) {
                        outputName = outputName.substring(0, outputName.length() - 1)
                    }
                    // 检查 tinkerManifestAction 的 outputNameToManifestMap 中是否已存在该输出名称
                    if (tinkerManifestAction.outputNameToManifestMap.containsKey(outputName)) {
                        throw new GradleException("Duplicate tinker manifest output name: '${outputName}'")
                    }
                    // 获取该输出的 AndroidManifest.xml 文件路径
                    def manifestPath = Compatibilities.getOutputManifestPath(project, agpProcessManifestTask, variantOutput)
                    // 将输出名称和对应的 AndroidManifest.xml 文件路径存入 outputNameToManifestMap 中
                    tinkerManifestAction.outputNameToManifestMap.put(outputName, manifestPath)
                }

                // 获取该变体的处理资源任务
                def agpProcessResourcesTask = Compatibilities.getProcessResourcesTask(project, variant)

                // 创建一个名为 tinkerProcess${capitalizedVariantName}ResourceId 的任务，用于处理资源 ID，类型为 TinkerResourceIdTask
                //resource id
                TinkerResourceIdTask applyResourceTask = mProject.tasks.create("tinkerProcess${capitalizedVariantName}ResourceId", TinkerResourceIdTask)
                applyResourceTask.variant = variant
                applyResourceTask.applicationId = Compatibilities.getApplicationId(project, variant)
                applyResourceTask.resDir = Compatibilities.getInputResourcesDirectory(project, agpProcessResourcesTask)

                // 设置 applyResourceTask 必须在 agpProcessManifestTask 之后执行
                applyResourceTask.mustRunAfter agpProcessManifestTask
                // 设置 agpProcessResourcesTask 依赖于 applyResourceTask
                agpProcessResourcesTask.dependsOn applyResourceTask

                // Fix issue-866.
                // We found some case that applyResourceTask run after mergeResourcesTask, it caused 'applyResourceMapping' config not work.
                // The task need merged resources to calculate ids.xml, it must depends on merge resources task.
                // 发现某些情况下 applyResourceTask 在 mergeResourcesTask 之后执行，导致 applyResourceMapping 配置不生效
                // 该任务需要合并后的资源来计算 ids.xml，因此必须依赖于合并资源任务
                def agpMergeResourcesTask = Compatibilities.getMergeResourcesTask(project, variant)
                // 设置 applyResourceTask 依赖于 agpMergeResourcesTask
                applyResourceTask.dependsOn agpMergeResourcesTask

                if (tinkerManifestAction.outputNameToManifestMap == null
                        || tinkerManifestAction.outputNameToManifestMap.isEmpty()) {
                    throw new GradleException('No manifest output path was found.')
                }

                if (applyResourceTask.resDir == null) {
                    throw new GradleException("applyResourceTask.resDir is null.")
                }

                // Add this proguard settings file to the list
                // 检查该变体的构建类型是否启用了代码混淆
                boolean proguardEnable = variant.getBuildType().buildType.minifyEnabled

                if (proguardEnable) {
                    // 获取该变体的混淆任务
                    def obfuscateTask = Compatibilities.getObfuscateTask(project, variant)
                    // 在 obfuscateTask 任务执行之前执行 TinkerProguardConfigAction
                    obfuscateTask.doFirst new TinkerProguardConfigAction(variant)
                }

                // Add this multidex proguard settings file to the list
                // 检查该变体是否启用了 MultiDex
                boolean multiDexEnabled = variant.mergedFlavor.multiDexEnabled

                if (multiDexEnabled) {
                    // 创建一个名为 tinkerProcess${capitalizedVariantName}MultidexKeep 的任务，用于处理 MultiDex 保持 ProGuard 规则，类型为 TinkerMultidexConfigTask
                    TinkerMultidexConfigTask multidexConfigTask = mProject.tasks.create("tinkerProcess${capitalizedVariantName}MultidexKeep", TinkerMultidexConfigTask)
                    multidexConfigTask.applicationVariant = variant
                    multidexConfigTask.multiDexKeepProguard = getManifestMultiDexKeepProguard(variant)

                    // for java.io.FileNotFoundException: app/build/intermediates/multi-dex/release/manifest_keep.txt
                    // for gradle 3.x gen manifest_keep move to processResources task
                    multidexConfigTask.mustRunAfter agpProcessResourcesTask

                    // 获取该变体的 MultiDex 任务
                    def agpMultidexTask = Compatibilities.getMultiDexTask(project, variant)
                    // 获取该变体的 R8 任务
                    def agpR8Task = Compatibilities.getR8Task(project, variant)
                    if (agpMultidexTask != null) {
                        agpMultidexTask.dependsOn multidexConfigTask
                    } else if (agpMultidexTask == null && agpR8Task != null) {
                        agpR8Task.dependsOn multidexConfigTask
                        try {
                            // 获取 R8 任务的 Transform 对象
                            Object r8Transform = agpR8Task.getTransform()
                            //R8 maybe forget to add multidex keep proguard file in agp 3.4.0, it's a agp bug!
                            //If we don't do it, some classes will not keep in maindex such as loader's classes.
                            //So tinker will not remove loader's classes, it will crashed in dalvik and will check TinkerTestDexLoad.isPatch failed in art.
                            if (r8Transform.metaClass.hasProperty(r8Transform, "mainDexRulesFiles")) {
                                File manifestMultiDexKeepProguard = getManifestMultiDexKeepProguard(variant)
                                if (manifestMultiDexKeepProguard != null) {
                                    //see difference between mainDexRulesFiles and mainDexListFiles in https://developer.android.com/studio/build/multidex?hl=zh-cn
                                    // 获取 r8Transform 的原始 mainDexRulesFiles 文件集合
                                    FileCollection originalFiles = r8Transform.metaClass.getProperty(r8Transform, 'mainDexRulesFiles')
                                    if (!originalFiles.contains(manifestMultiDexKeepProguard)) {
                                        FileCollection replacedFiles = mProject.files(originalFiles, manifestMultiDexKeepProguard)
                                        mProject.logger.error("R8Transform original mainDexRulesFiles: ${originalFiles.files}")
                                        mProject.logger.error("R8Transform replaced mainDexRulesFiles: ${replacedFiles.files}")
                                        //it's final, use reflect to replace it.
                                        // 由于 mainDexRulesFiles 是 final 字段，使用反射替换该字段的值
                                        replaceKotlinFinalField("com.android.build.gradle.internal.transforms.R8Transform", "mainDexRulesFiles", r8Transform, replacedFiles)
                                    }
                                }
                            }
                        } catch (Exception ignore) {
                            //Maybe it's not a transform task after agp 3.6.0 so try catch it.
                        }
                    }
                    // 获取该变体的收集 MultiDex 组件任务
                    def collectMultiDexComponentsTask = Compatibilities.getCollectMultiDexComponentsTask(project, variant)
                    if (collectMultiDexComponentsTask != null) {
                        multidexConfigTask.mustRunAfter collectMultiDexComponentsTask
                    }
                }

                // 检查 buildConfig 中的 keepDexApply 配置是否为 true，并且旧 APK 文件是否合法
                if (configuration.buildConfig.keepDexApply
                        && FileOperation.isLegalFile(mProject.tinkerPatch.oldApk)) {
                    // 如果满足条件，注入 ImmutableDexTransform
                    com.tencent.tinker.build.gradle.transform.ImmutableDexTransform.inject(mProject, variant)
                }
            }
        }
    }

    /**
     * Specify the output folder of tinker patch result.
     * 指定 Tinker 补丁结果的输出文件夹。
     *
     * @param configuration the tinker configuration 'tinkerPatch'
     * @param output the output of assemble result
     * @param variant the variant
     * @param tinkerPatchBuildTask the task that tinker patch uses
     */
    void setPatchOutputFolder(configuration, output, variant, tinkerPatchBuildTask) {
        File parentFile = output.outputFile
        String outputFolder = "${configuration.outputFolder}";
        if (!Utils.isNullOrNil(outputFolder)) {
            outputFolder = "${outputFolder}/${TypedValue.PATH_DEFAULT_OUTPUT}/${variant.dirName}"
        } else {
            outputFolder =
                    "${parentFile.getParentFile().getParentFile().getAbsolutePath()}/${TypedValue.PATH_DEFAULT_OUTPUT}/${variant.dirName}"
        }
        tinkerPatchBuildTask.outputFolder = outputFolder
    }

    void disableArchiveDex() {
        try {
            def booleanOptClazz = Class.forName('com.android.build.gradle.options.BooleanOption')
            def enableDexArchiveField = booleanOptClazz.getDeclaredField('ENABLE_DEX_ARCHIVE')
            enableDexArchiveField.setAccessible(true)
            def enableDexArchiveEnumObj = enableDexArchiveField.get(null)
            def defValField = enableDexArchiveEnumObj.getClass().getDeclaredField('defaultValue')
            defValField.setAccessible(true)
            defValField.set(enableDexArchiveEnumObj, false)
        } catch (Throwable thr) {
            // To some extends, class not found means we are in lower version of android gradle
            // plugin, so just ignore that exception.
            if (!(thr instanceof ClassNotFoundException)) {
                mProject.logger.error("reflectDexArchiveFlag error: ${thr.getMessage()}.")
            }
        }
    }

    /**
     * Specify the new apk path. If the new apk file is specified by {@code tinkerPatch.buildConfig.newApk},
     * just use it as the new apk input for tinker patch, otherwise use the assemble output.
     * 用于指定 Tinker 补丁的新 APK 路径。如果在配置中指定了新的 APK 文件路径，则使用该路径作为 Tinker 补丁的新 APK 输入；否则，
     * 使用 assemble 任务的输出文件作为新 APK 输入。
     *
     * @param project the project which applies this plugin
     * @param configuration the tinker configuration 'tinkerPatch'
     * @param output the output of assemble result
     * @param variant the variant
     * @param tinkerPatchBuildTask the task that tinker patch uses
     */
    void setPatchNewApkPath(configuration, output, variant, tinkerPatchBuildTask) {
        def newApkPath = configuration.newApk
        if (!Utils.isNullOrNil(newApkPath)) {
            if (FileOperation.isLegalFileOrDirectory(newApkPath)) {
                tinkerPatchBuildTask.buildApkPath = newApkPath
                return
            }
        }

        tinkerPatchBuildTask.buildApkPath = output.outputFile

        tinkerPatchBuildTask.dependsOn Compatibilities.getAssembleTask(mProject, variant)
    }

    // 替换 Kotlin 类中 final 字段值的方法
    void replaceKotlinFinalField(String className, String filedName, Object instance, Object fieldValue) {
        Field field = Class.forName(className).getDeclaredField(filedName)
        final Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe")
        unsafeField.setAccessible(true)
        final Unsafe unsafe = (Unsafe) unsafeField.get(null)
        final long fieldOffset = unsafe.objectFieldOffset(field)
        unsafe.putObject(instance, fieldOffset, fieldValue)
    }

    // 获取 ManifestMultiDexKeepProguard 文件的方法
    File getManifestMultiDexKeepProguard(def applicationVariant) {
        File multiDexKeepProguard = null

        try {
            def file = applicationVariant.variantData.artifacts.get(
                    Class.forName('com.android.build.gradle.internal.scope.InternalArtifactType$LEGACY_MULTIDEX_AAPT_DERIVED_PROGUARD_RULES')
                            .getDeclaredField("INSTANCE")
                            .get(null)
            ).getOrNull()?.getAsFile()
            if (file != null && file.getName() != '__EMPTY_DIR__') {
                multiDexKeepProguard = file
            }
        } catch (Throwable ignore) {
            // Ignored.
        }

        if (multiDexKeepProguard == null) {
            try {
                //for kotlin
                def file = applicationVariant.getVariantData().getScope().getArtifacts().getFinalProduct(
                        Class.forName('com.android.build.gradle.internal.scope.InternalArtifactType$LEGACY_MULTIDEX_AAPT_DERIVED_PROGUARD_RULES')
                                .getDeclaredField("INSTANCE")
                                .get(null)
                ).getOrNull()?.getAsFile()
                if (file != null && file.getName() != '__EMPTY_DIR__') {
                    multiDexKeepProguard = file
                }
            } catch (Throwable ignore) {
                // Ignored.
            }
        }

        if (multiDexKeepProguard == null) {
            try {
                File file = applicationVariant.getVariantData().getScope().getArtifacts().getFinalProduct(
                        Class.forName("com.android.build.gradle.internal.scope.InternalArtifactType")
                                .getDeclaredField("LEGACY_MULTIDEX_AAPT_DERIVED_PROGUARD_RULES")
                                .get(null)
                ).getOrNull()?.getAsFile()
                if (file != null && file.getName() != '__EMPTY_DIR__') {
                    multiDexKeepProguard = file
                }
            } catch (Throwable ignore) {
                // Ignored.
            }
        }

        if (multiDexKeepProguard == null) {
            try {
                def buildableArtifact = applicationVariant.getVariantData().getScope().getArtifacts().getFinalArtifactFiles(
                        Class.forName("com.android.build.gradle.internal.scope.InternalArtifactType")
                                .getDeclaredField("LEGACY_MULTIDEX_AAPT_DERIVED_PROGUARD_RULES")
                                .get(null)
                )

                //noinspection GroovyUncheckedAssignmentOfMemberOfRawType,UnnecessaryQualifiedReference
                multiDexKeepProguard = com.google.common.collect.Iterators.getOnlyElement(buildableArtifact.iterator())
            } catch (Throwable ignore) {

            }
        }

        if (multiDexKeepProguard == null) {
            try {
                multiDexKeepProguard = applicationVariant.getVariantData().getScope().getManifestKeepListProguardFile()
            } catch (Throwable ignore) {

            }
        }

        if (multiDexKeepProguard == null) {
            try {
                multiDexKeepProguard = applicationVariant.getVariantData().getScope().getManifestKeepListFile()
            } catch (Throwable ignore) {

            }
        }

        if (multiDexKeepProguard == null) {
            mProject.logger.error("can't get multiDexKeepProguard file")
        }

        return multiDexKeepProguard
    }
}
