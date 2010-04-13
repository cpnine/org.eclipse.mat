/*******************************************************************************
 * Copyright (c) 2010 IBM Corporation
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    IBM Corporation - initial implementation
 *******************************************************************************/
package org.eclipse.mat.ibmvm.acquire;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.annotations.Argument;
import org.eclipse.mat.query.annotations.Name;
import org.eclipse.mat.snapshot.acquire.IHeapDumpProvider;
import org.eclipse.mat.snapshot.acquire.VmInfo;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.IProgressListener.Severity;

@Name("IBM Dump (using helper VM)")
public class IBMExecDumpProvider extends BaseProvider
{

    private static final String PLUGIN_ID = "org.eclipse.mat.ibmdump"; //$NON-NLS-1$
    private static final String JAVA_EXEC = "java"; //$NON-NLS-1$
    private static boolean abort = false;
    
    @Argument
    public File javaexecutable;

    public File acquireDump(VmInfo info, File preferredLocation, IProgressListener listener) throws SnapshotException
    {
        listener.beginTask(Messages.getString("IBMExecDumpProvider.GeneratingDump"), TOTAL_WORK); //$NON-NLS-1$
        ProcessBuilder pb = new ProcessBuilder();
        Process p = null;
        final IBMVmInfo info2 = (IBMVmInfo) info;
        String vm = (info2).getPidName();
        try
        {
            String jar = getExecJar().getAbsolutePath();
            final String execPath = javaexecutable.getPath();
            pb.command(execPath, "-jar", jar, info2.type.toString(), vm, info.getProposedFileName()); //$NON-NLS-1$
            p = pb.start();
            StringBuffer err = new StringBuffer();
            StringBuffer in = new StringBuffer();
            InputStreamReader os = new InputStreamReader(p.getInputStream());
            try
            {
                InputStreamReader es = new InputStreamReader(p.getErrorStream());
                try
                {
                    int rc = 0;
                    do
                    {
                        while (os.ready())
                        {
                            in.append((char) os.read());
                        }
                        while (es.ready())
                        {
                            int c = es.read();
                            if (c == '.')
                                listener.worked(1);
                            err.append((char) c);
                        }
                        try
                        {
                            rc = p.exitValue();
                            break;
                        }
                        catch (IllegalThreadStateException e)
                        {
                            try
                            {
                                Thread.sleep(SLEEP_TIMEOUT);
                            }
                            catch (InterruptedException e1)
                            {
                                listener.setCanceled(true);
                            }
                        }
                        if (listener.isCanceled())
                        {
                            return null;
                        }
                    }
                    while (true);
                    if (rc != 0) 
                    {
                        throw new IOException(MessageFormat.format(Messages
                                    .getString("IBMExecDumpProvider.ReturnCode"), execPath, rc, err.toString())); //$NON-NLS-1$
                    }
                    String ss[] = in.toString().split("[\\n\\r]+"); //$NON-NLS-1$
                    String filename = ss[0];
                    listener.done();
                    final File file = new File(filename);
                    if (!file.canRead()) { throw new FileNotFoundException(filename); }
                    return file;
                }
                finally
                {
                    es.close();
                }
            }
            finally
            {
                try
                {
                    p.exitValue();
                }
                catch (IllegalThreadStateException e)
                {
                    p.destroy();
                }
                os.close();
            }
        }
        catch (FileNotFoundException e)
        {
            throw new SnapshotException(e);
        }
        catch (IOException e)
        {
            throw new SnapshotException(e);
        }
    }

    private File javaExec(String dir)
    {
        return jvmExec(dir, JAVA_EXEC);
    }

    static File jvmExec(String javaDir, String exec)
    {
        File javaExec;
        if (javaDir != null)
        {
            javaExec = new File(javaDir, exec);
        }
        else
        {
            javaExec = new File(exec);
        }
        return javaExec;
    }

    public List<VmInfo> getAvailableVMs(IProgressListener listener)
    {
        List<VmInfo> ret;
        if (abort)
            return null;
        /*
         * 1.Try previous/no directory 2.Query directory - based on
         * previous/Java.home
         */

        File javaExec = javaexecutable;
        String javaDir;
        
        if (javaExec != null)
        {
            javaDir = javaExec.getParent();
            System.out.println("argument "+javaexecutable+" "+javaDir);
        }
        else
        {
            javaDir = getJavaDir();

            if (javaDir == null)
                javaDir = defaultJavaDir();

            javaExec = javaExec(javaDir);
            System.out.println("find "+javaExec+" "+javaDir);
        }

        ret = execGetVMs(javaExec, listener);
        if (ret != null)
        {
            setJavaDir(javaDir);
            this.javaexecutable = javaExec;
        }
        return ret;
    }

    /**
     * Guess a suitable VM to suggest to the user
     * 
     * @return
     */
    private String defaultJavaDir()
    {
        String path = System.getenv("PATH"); //$NON-NLS-1$
        if (path != null)
        {
            for (String p : path.split(File.pathSeparator))
            {
                File dir = new File(p);
                File parentDir = dir.getParentFile();
                // Recent IBM VMs have diagnostics collector and late attach
                File dll = new File(dir, "dgcollector.dll"); //$NON-NLS-1$
                if (dll.exists())
                {
                    return dir.getPath();
                }
                dll = new File(p, "dgcollector.so"); //$NON-NLS-1$
                if (dll.exists())
                {
                    return dir.getPath();
                }
                // Perhaps we were given the sdk/bin directory, so look for the
                // sdk/jre/bin
                dir = new File(parentDir, "jre"); //$NON-NLS-1$
                dir = new File(dir, "bin"); //$NON-NLS-1$
                dll = new File(dir, "dgcollector.dll"); //$NON-NLS-1$
                if (dll.exists())
                {
                    return dir.getPath();
                }
                dll = new File(p, "dgcollector.so"); //$NON-NLS-1$
                if (dll.exists())
                {
                    return dir.getPath();
                }
            }
        }
        String home = System.getProperty("java.home"); //$NON-NLS-1$
        return new File(home, "bin").getPath(); //$NON-NLS-1$
    }

    private static final String last_directory_key = IBMExecDumpProvider.class.getName() + ".lastDir"; //$NON-NLS-1$
    private static String savedJavaDir;

    private static synchronized String getJavaDir()
    {
        String home = Platform.getPreferencesService().getString(PLUGIN_ID, last_directory_key, savedJavaDir, null);
        return home;
    }

    private static synchronized void setJavaDir(String home)
    {
        new InstanceScope().getNode(PLUGIN_ID).put(last_directory_key, home);
        savedJavaDir = home;
    }

    private List<VmInfo> execGetVMs(File javaExec, IProgressListener listener)
    {
        ArrayList<VmInfo> ar = new ArrayList<VmInfo>();
        ProcessBuilder pb = new ProcessBuilder();
        Process p = null;
        final String execPath = javaExec.getPath();
        try
        {
            String jar = getExecJar().getAbsolutePath();
            pb.command(execPath, "-jar", jar); //$NON-NLS-1$
            p = pb.start();
            StringBuffer err = new StringBuffer();
            StringBuffer in = new StringBuffer();
            InputStreamReader os = new InputStreamReader(p.getInputStream());
            try
            {
                InputStreamReader es = new InputStreamReader(p.getErrorStream());
                try
                {
                    int rc = 0;
                    do
                    {
                        while (os.ready())
                        {
                            in.append((char) os.read());
                        }
                        while (es.ready())
                        {
                            err.append((char) es.read());
                        }
                        try
                        {
                            rc = p.exitValue();
                            break;
                        }
                        catch (IllegalThreadStateException e)
                        {
                            Thread.sleep(100);
                        }
                    }
                    while (true);
                    if (rc != 0)
                    {
                        listener.sendUserMessage(Severity.WARNING,
                                        MessageFormat.format(Messages.getString("IBMExecDumpProvider.ProblemListingVMsRC"), execPath, rc, err.toString()), null); //$NON-NLS-1$
                        ar = null;
                        return ar;
                    }
                    String ss[] = in.toString().split("[\\n\\r]+"); //$NON-NLS-1$
                    for (String s : ss)
                    {
                        // pid,proposed filename,description
                        String s2[] = s.split(",", 3); //$NON-NLS-1$
                        if (s2.length >= 3)
                        {
                            // Exclude the helper process
                            if (!s2[2].contains(getExecJar().getName()))
                            {
                                IBMExecVmInfo ifo = new IBMExecVmInfo();
                                ifo.setPid(s2[0]);
                                ifo.setProposedFileName(s2[1]);
                                ifo.setDescription(s2[2]);
                                ifo.setHeapDumpProvider(this);
                                ifo.javaexecutable = javaExec;
                                ar.add(ifo);
                            }
                        }
                    }
                }
                finally
                {
                    es.close();
                }
            }
            finally
            {
                os.close();
            }
        }
        catch (IOException e)
        {
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(Messages.getString("IBMExecDumpProvider.ProblemListingVMs"), execPath), e); //$NON-NLS-1$
            ar = null;
        }
        catch (InterruptedException e)
        {
            listener.sendUserMessage(Severity.WARNING, MessageFormat.format(Messages.getString("IBMExecDumpProvider.ProblemListingVMs"), execPath), e); //$NON-NLS-1$
            ar = null;
        }
        return ar;
    }

    private static File execJar;

    static synchronized File getExecJar() throws IOException
    {
        if (execJar == null || !execJar.canRead())
        {
            String jarname = "org.eclipse.mat.ibmexecdumps"; //$NON-NLS-1$
            String classesNames[] = {"org.eclipse.mat.ibmvm.acquire.IBMDumpProvider", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMDumpProvider$AgentLoader", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMDumpProvider$FileComparator", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMDumpProvider$NewFileFilter", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMDumpProvider$StderrProgressListener", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.BaseProvider", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMHeapDumpProvider", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMSystemDumpProvider", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMVmInfo", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.AgentLoader2", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.acquire.IBMVmInfo$DumpType", //$NON-NLS-1$
                            "org.eclipse.mat.ibmvm.agent.DumpAgent" }; //$NON-NLS-1$
            Class<?> classes[] = { SnapshotException.class, IHeapDumpProvider.class, VmInfo.class,
                            IProgressListener.class, IProgressListener.OperationCanceledException.class,
                            Severity.class, Messages.class, };
            execJar = makeJar(jarname, "Main-Class: ", classesNames, classes); //$NON-NLS-1$
        }
        return execJar;
    }

}
