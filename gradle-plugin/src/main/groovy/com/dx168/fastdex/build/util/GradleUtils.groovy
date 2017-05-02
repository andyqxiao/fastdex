package com.dx168.fastdex.build.util

import com.android.build.api.transform.Format
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.builder.model.Version
import com.google.common.collect.Lists
import com.android.build.gradle.internal.transforms.JarMerger
import fastdex.common.utils.FileUtils
import groovy.xml.QName
import org.gradle.api.GradleException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Status
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.pipeline.TransformInvocationBuilder
import com.google.common.collect.ImmutableList
import com.android.build.api.transform.Transform
import org.gradle.api.Project

/**
 * Created by tong on 17/3/14.
 */
public class GradleUtils {
    public static final String ANDROID_GRADLE_PLUGIN_VERSION = Version.ANDROID_GRADLE_PLUGIN_VERSION

    /**
     * 获取指定variant的依赖列表
     * @param project
     * @param applicationVariant
     * @return
     */
    public static Set<String> getCurrentDependList(Project project,Object applicationVariant) {
        String buildTypeName = applicationVariant.getBuildType().buildType.getName()

        Set<String> result = new HashSet<>()

        project.configurations.compile.each { File file ->
            //project.logger.error("==fastdex compile: ${file.absolutePath}")
            result.add(file.getAbsolutePath())
        }

        project.configurations."${buildTypeName}Compile".each { File file ->
            //project.logger.error("==fastdex ${buildTypeName}Compile: ${file.absolutePath}")
            result.add(file.getAbsolutePath())
        }

//        project.configurations.all.findAll { !it.allDependencies.empty }.each { c ->
//            if (c.name.toString().equals("compile")
//                    || c.name.toString().equals("apt")
//                    || c.name.toString().equals("_${buildTypeName}Compile".toString())) {
//                c.allDependencies.each { dep ->
//                    String depStr =  "$dep.group:$dep.name:$dep.version"
//                    if (!"null:unspecified:null".equals(depStr)) {
//                        result.add(depStr)
//                    }
//                }
//            }
//        }
        return result
    }

    /**
     * 获取transformClassesWithDexFor${variantName}任务的dex输出目录
     * @param transformInvocation
     * @return
     */
    public static File getDexOutputDir(Project project,Transform realTransform,TransformInvocation transformInvocation) {
        def outputProvider = transformInvocation.getOutputProvider()
        def outputDir = null
        String androidGradlePluginVersion = ANDROID_GRADLE_PLUGIN_VERSION

        if (androidGradlePluginVersion.startsWith("2.4.")) {
            outputDir = outputProvider.getContentLocation(
                            "main",
                            realTransform.getOutputTypes(),
                            TransformManager.SCOPE_FULL_PROJECT,
                            Format.DIRECTORY)

            return outputDir
        }

        List<JarInput> jarInputs = Lists.newArrayList();
        List<DirectoryInput> directoryInputs = Lists.newArrayList();
        for (TransformInput input : transformInvocation.getInputs()) {
            jarInputs.addAll(input.getJarInputs());
            directoryInputs.addAll(input.getDirectoryInputs());
        }

        if (androidGradlePluginVersion.compareTo("2.3.0") < 0) {
            //2.3.0以前的版本
            if ((jarInputs.size() + directoryInputs.size()) == 1
                    || !realTransform.dexOptions.getPreDexLibraries()) {
                outputDir = outputProvider.getContentLocation("main",
                        realTransform.getOutputTypes(), realTransform.getScopes(),
                        Format.DIRECTORY);
            }
            else {
                outputDir = outputProvider.getContentLocation("main",
                        TransformManager.CONTENT_DEX, realTransform.getScopes(),
                        Format.DIRECTORY);
            }
        }
        else {
            //2.3.0以后的版本包括2.3.0
            if ((jarInputs.size() + directoryInputs.size()) == 1
                    || !realTransform.dexOptions.getPreDexLibraries()) {
                outputDir = outputProvider.getContentLocation("main",
                        realTransform.getOutputTypes(),
                        TransformManager.SCOPE_FULL_PROJECT,
                        Format.DIRECTORY);
            }
            else {
                outputDir = outputProvider.getContentLocation("main",
                        TransformManager.CONTENT_DEX, TransformManager.SCOPE_FULL_PROJECT,
                        Format.DIRECTORY);
            }
        }
        return outputDir;
    }

    /**
     * 获取AndroidManifest.xml文件package属性值
     * @param manifestPath
     * @return
     */
    public static String getPackageName(String manifestPath) {
        def xml = new XmlParser().parse(new InputStreamReader(new FileInputStream(manifestPath), "utf-8"))
        String packageName = xml.attribute('package')

        return packageName
    }

    /**
     * 获取启动的activity
     * @param manifestPath
     * @return
     */
    public static String getBootActivity(String manifestPath) {
        def bootActivityName = ""
        def xml = new XmlParser().parse(new InputStreamReader(new FileInputStream(manifestPath), "utf-8"))
        def application = xml.application[0]

        if (application) {
            def activities = application.activity
            QName androidNameAttr = new QName("http://schemas.android.com/apk/res/android", 'name', 'android');

            try {
                activities.each { activity->
                    def activityName = activity.attribute(androidNameAttr)

                    if (activityName) {
                        def intentFilters = activity."intent-filter"
                        if (intentFilters) {
                            intentFilters.each { intentFilter->
                                def actions = intentFilter.action
                                def categories = intentFilter.category
                                if (actions && categories) {
                                    //android.intent.action.MAIN
                                    //android.intent.category.LAUNCHER

                                    boolean hasMainAttr = false
                                    boolean hasLauncherAttr = false

                                    actions.each { action ->
                                        def attr = action.attribute(androidNameAttr)
                                        if ("android.intent.action.MAIN".equals(attr.toString())) {
                                            hasMainAttr = true
                                        }
                                    }

                                    categories.each { categoriy ->
                                        def attr = categoriy.attribute(androidNameAttr)
                                        if ("android.intent.category.LAUNCHER".equals(attr.toString())) {
                                            hasLauncherAttr = true
                                        }
                                    }

                                    if (hasMainAttr && hasLauncherAttr) {
                                        bootActivityName = activityName
                                        throw new JumpException()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (JumpException e) {

            }
        }
        return bootActivityName
    }

    /**
     * 合并所有的代码到一个jar钟
     * @param project
     * @param transformInvocation
     * @param outputJar             输出路径
     */
    public static void executeMerge(Project project,TransformInvocation transformInvocation, File outputJar) {
        List<JarInput> jarInputs = Lists.newArrayList();
        List<DirectoryInput> dirInputs = Lists.newArrayList();

        for (TransformInput input : transformInvocation.getInputs()) {
            jarInputs.addAll(input.getJarInputs());
        }

        for (TransformInput input : transformInvocation.getInputs()) {
            dirInputs.addAll(input.getDirectoryInputs());
        }

        JarMerger jarMerger = getClassJarMerger(outputJar)
        jarInputs.each { jar ->
            project.logger.error("==fastdex merge jar " + jar.getFile())
            jarMerger.addJar(jar.getFile())
        }
        dirInputs.each { dir ->
            project.logger.error("==fastdex merge dir " + dir)
            jarMerger.addFolder(dir.getFile())
        }
        jarMerger.close()
        if (!FileUtils.isLegalFile(outputJar)) {
            throw new GradleException("merge jar fail: \n jarInputs: ${jarInputs}\n dirInputs: ${dirInputs}\n mergedJar: ${outputJar}")
        }
        project.logger.error("==fastdex merge jar success: ${outputJar}")
    }

    private static JarMerger getClassJarMerger(File jarFile) {
        JarMerger jarMerger = new JarMerger(jarFile)

        Class<?> zipEntryFilterClazz
        try {
            zipEntryFilterClazz = Class.forName("com.android.builder.packaging.ZipEntryFilter")
        } catch (Throwable t) {
            zipEntryFilterClazz = Class.forName("com.android.builder.signing.SignedJarBuilder\$IZipEntryFilter")
        }

        Class<?>[] classArr = new Class[1];
        classArr[0] = zipEntryFilterClazz
        InvocationHandler handler = new InvocationHandler(){
            public Object invoke(Object proxy, Method method, Object[] args)
                    throws Throwable {
                return args[0].endsWith(Constants.CLASS_SUFFIX);
            }
        };
        Object proxy = Proxy.newProxyInstance(zipEntryFilterClazz.getClassLoader(), classArr, handler);

        jarMerger.setFilter(proxy);

        return jarMerger
    }

    public static TransformInvocation createNewTransformInvocation(Transform transform,TransformInvocation transformInvocation,File inputJar) {
        TransformInvocationBuilder builder = new TransformInvocationBuilder(transformInvocation.getContext());
        builder.addInputs(jarFileToInputs(transform,inputJar))
        builder.addOutputProvider(transformInvocation.getOutputProvider())
        builder.addReferencedInputs(transformInvocation.getReferencedInputs())
        builder.addSecondaryInputs(transformInvocation.getSecondaryInputs())
        builder.setIncrementalMode(transformInvocation.isIncremental())

        return builder.build()
    }

    /**
     * change the jar file to TransformInputs
     */
    private static Collection<TransformInput> jarFileToInputs(Transform transform,File jarFile) {
        TransformInput transformInput = new TransformInput() {
            @Override
            Collection<JarInput> getJarInputs() {
                JarInput jarInput = new JarInput() {
                    @Override
                    Status getStatus() {
                        return Status.ADDED
                    }

                    @Override
                    String getName() {
                        return jarFile.getName().substring(0,
                                jarFile.getName().length() - ".jar".length())
                    }

                    @Override
                    File getFile() {
                        return jarFile
                    }

                    @Override
                    Set<QualifiedContent.ContentType> getContentTypes() {
                        return transform.getInputTypes()
                    }

                    @Override
                    Set<QualifiedContent.Scope> getScopes() {
                        return transform.getScopes()
                    }
                }
                return ImmutableList.of(jarInput)
            }


            @Override
            Collection<DirectoryInput> getDirectoryInputs() {
                return ImmutableList.of()
            }
        }
        return ImmutableList.of(transformInput)
    }
}
