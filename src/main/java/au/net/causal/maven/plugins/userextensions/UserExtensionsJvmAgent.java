package au.net.causal.maven.plugins.userextensions;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class UserExtensionsJvmAgent
{
    public static void premain(String agentArgs, Instrumentation inst)
    {
        inst.addTransformer(new ClassFileTransformer()
        {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException
            {
                if ("org/apache/maven/cli/MavenCli".equals(className))
                {
                    try
                    {
                        ClassPool classPool = new ClassPool(null);
                        classPool.appendClassPath(new LoaderClassPath(loader));

                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));

                        CtMethod loadCoreExtensionsMethod = ctClass.getDeclaredMethod("loadCoreExtensions");
                        transformLoadCoreExtensionsMethod(loadCoreExtensionsMethod);

                        classfileBuffer = ctClass.toBytecode();
                        ctClass.detach();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                return classfileBuffer;
            }
        });
    }

    private static void transformLoadCoreExtensionsMethod(CtMethod m)
    throws CannotCompileException
    {
        m.instrument(new ExprEditor()
        {
            @Override
            public void edit(MethodCall c) throws CannotCompileException
            {
                //Want to always get to readCoreExtensionsDescriptor() code in MavenCli.loadCoreExtensions()
                //so we ensure this code:

//                File extensionsFile = new File( cliRequest.multiModuleProjectDirectory, EXTENSIONS_FILENAME );
//                if ( !extensionsFile.isFile() )
//                {
//                    return Collections.emptyList();
//                }

                //always returns true by changing the isFile() call to omit the check if we are dealing with a file named ".../.mvn/extensions.xml"

                if (File.class.getName().equals(c.getClassName()) && "isFile".equals(c.getMethodName()))
                {
                    c.replace("if (\"extensions.xml\".equals($0.getName()) && \".mvn\".equals($0.getParentFile().getName())) { " +
                    "  $_ = true; " +
                    "} else { $_ = $proceed($$); }");
                }



                //readCoreExtensionsDescriptor() call needs to be updated
                // - if project-specific extensions.xml file exists, call readCoreExtensionsDescriptor() and save results to list
                // - if user-global extensions.xml in .m2 directory exists, cal readCoreExtensionsDescriptor() with this file and save results to list
                //TODO merge the entries
                else if ("org.apache.maven.cli.MavenCli".equals(c.getClassName()) && "readCoreExtensionsDescriptor".equals(c.getMethodName()))
                {
                    c.replace(
                            //Initialize list
                            "$_ = new java.util.ArrayList();" +

                            //Read project extensions.xml file first, if file exists
                            "if ($1.isFile()) { $_.addAll($proceed($$)); } " +


                            //Now try a user-global extensions.xml file, if it exists
                            "if (new java.io.File(USER_MAVEN_CONFIGURATION_HOME, \"extensions.xml\").isFile()) { $_.addAll($proceed(new java.io.File(USER_MAVEN_CONFIGURATION_HOME, \"extensions.xml\"))); }"
                    );
                }
            }
        });
    }
}
