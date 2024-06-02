/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.integration.ee.appclient.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;


/**
 * @author Dominik Pospisil <dpospisi@redhat.com>
 * @author Stuart Douglas
 */
public class AppClientWrapper implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AppClientWrapper.class);

    private String appClientCommand = null;

    private static final String outThreadHame = "APPCLIENT-out";
    private static final String errThreadHame = "APPCLIENT-err";

    private Process appClientProcess;
    private BufferedReader outputReader;
    private BufferedReader errorReader;
    private BlockingQueue<String> outputQueue = new LinkedBlockingQueue<String>();
    private Thread shutdownThread;
    private final Archive<?> archive;
    private final String clientArchiveName;
    private final String appClientArgs;
    private File archiveOnDisk;
    private final String args;

    /**
     * Creates new CLI wrapper. If the connect parameter is set to true the CLI
     * will connect to the server using <code>connect</code> command.
     *
     *
     * @param archive
     * @param clientArchiveName
     * @param args
     * @throws Exception
     */
    public AppClientWrapper(final Archive<?> archive,final String appClientArgs, final String clientArchiveName, final String args) throws Exception {
        this.archive = archive;
        this.clientArchiveName = clientArchiveName;
        this.args = args;
        this.appClientArgs = appClientArgs;
        init();
    }

    /**
     * Consumes all available output from App Client.
     *
     * @param timeout number of milliseconds to wait for each subsequent line
     * @return array of App Client output lines
     */
    public String[] readAll(final long timeout) {
        Vector<String> lines = new Vector<String>();
        String line = null;
        do {
            try {
                line = outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
                if (line != null) lines.add(line);
            } catch (InterruptedException ioe) {
            }

        } while (line != null);
        return lines.toArray(new String[]{});
    }

    /**
     * Consumes all available output from CLI.
     *
     * @param timeout number of milliseconds to wait for each subsequent line
     * @return array of CLI output lines
     */
    public String readAllUnformated(long timeout) {
        String[] lines = readAll(timeout);
        StringBuilder buf = new StringBuilder();
        for (String line : lines) buf.append(line + "\n");
        return buf.toString();

    }

    /**
     * Kills the app client
     *
     * @throws Exception
     */
    public synchronized void quit() throws Exception {
        appClientProcess.destroy();
        try {
            appClientProcess.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Runtime.getRuntime().removeShutdownHook(shutdownThread);
        if (archiveOnDisk != null) {
            archiveOnDisk.delete();
        }
    }


    private void init() throws Exception {
        shutdownThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (appClientProcess != null) {
                    appClientProcess.destroy();
                    if (archiveOnDisk != null) {
                        archiveOnDisk.delete();
                    }
                    try {
                        appClientProcess.waitFor();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownThread);
        appClientProcess = Runtime.getRuntime().exec(getAppClientCommand());
        new PrintWriter(appClientProcess.getOutputStream());
        outputReader = new BufferedReader(new InputStreamReader(appClientProcess.getInputStream(), StandardCharsets.UTF_8));
        errorReader = new BufferedReader(new InputStreamReader(appClientProcess.getErrorStream(), StandardCharsets.UTF_8));

        final Thread readOutputThread  = Thread.ofVirtual().name(outThreadHame).unstarted(this);
        readOutputThread.start();
        final Thread readErrorThread  = Thread.ofVirtual().name(errThreadHame).unstarted(this);
        readErrorThread.start();

    }

    private String getAppClientCommand() throws Exception {
        if (appClientCommand != null) return appClientCommand;

        final String tempDir = System.getProperty("java.io.tmpdir");
        archiveOnDisk = new File(tempDir + File.separator + archive.getName());
        if(archiveOnDisk.exists()) {
            archiveOnDisk.delete();
        }
        final ZipExporter exporter = archive.as(ZipExporter.class);
        exporter.exportTo(archiveOnDisk);
        final String archivePath = archiveOnDisk.getAbsolutePath();

        // We don't directly pass the archive file and deployment name to appclient's main
        // Instead we prove expressions work by passing an expression
        final String archiveArg;
        if(clientArchiveName == null) {
            archiveArg = "${test.expr.appclient.file: " + archivePath + "}";
        } else {
            archiveArg = "${test.expr.appclient.file:" + archivePath + "}#${test.expr.appclient.deployment:" + clientArchiveName + "}";
        }

        // TODO: Move to a shared testsuite lib.
        String asDist = System.getProperty("jboss.dist");
        if( asDist == null ) throw new Exception("'jboss.dist' property is not set.");
        if( ! new File(asDist).exists() ) throw new Exception("AS dir from 'jboss.dist' doesn't exist: " + asDist + " user.dir: " + System.getProperty("user.dir"));

        String instDir = System.getProperty("jboss.install.dir");
        if( instDir == null ) throw new Exception("'jboss.install.dir' property is not set.");
        if( ! new File(instDir).exists() ) throw new Exception("AS dir from 'jboss.install.dir' doesn't exist: " + instDir + " user.dir: " + System.getProperty("user.dir"));

        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

        appClientCommand = java +
                " -Djboss.modules.dir="+ asDist + "/modules" +
                " -Djline.WindowsTerminal.directConsole=false" +
                TestSuiteEnvironment.getIpv6Args() +
                "-Djboss.bind.address=" + TestSuiteEnvironment.getServerAddress() +
                " "+System.getProperty("server.jvm.args") +
                " -jar "+ instDir + "/jboss-modules.jar" +
                " -mp "+ asDist + "/modules" +
                " org.jboss.as.appclient" +
                " -Djboss.server.base.dir="+ instDir + "/appclient" +
                " -Djboss.home.dir="+ instDir +
                " " + this.appClientArgs + " " + archiveArg + " " + args;

        System.out.println(appClientCommand);
        return appClientCommand;
    }

    /**
     *
     */
    public void run() {
        final String threadName = Thread.currentThread().getName();
        final BufferedReader reader = threadName.equals(outThreadHame) ? outputReader : errorReader;
        try {
            String line = reader.readLine();
            while (line != null) {
                if (threadName.equals(outThreadHame))
                    outputLineReceived(line);
                else
                    errorLineReceived(line);
                line = reader.readLine();
            }
        } catch (Exception e) {
        } finally {
            synchronized (this) {
                if (threadName.equals(outThreadHame))
                    outputReader = null;
                else
                    errorReader = null;
            }
        }
    }

    private synchronized void outputLineReceived(String line) {
        LOGGER.info("[" + outThreadHame + "] " + line);
        outputQueue.add(line);
    }

    private synchronized void errorLineReceived(String line) {
        LOGGER.info("[" + outThreadHame + "] " + line);
    }

}
