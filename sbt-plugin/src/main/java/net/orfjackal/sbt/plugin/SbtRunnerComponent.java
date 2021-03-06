// Copyright © 2010, Esko Luontola <www.orfjackal.net>
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package net.orfjackal.sbt.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareRunnable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import net.orfjackal.sbt.plugin.settings.*;
import net.orfjackal.sbt.runner.*;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class SbtRunnerComponent extends AbstractProjectComponent implements DumbAware {

    private static final Logger logger = Logger.getInstance(SbtRunnerComponent.class.getName());
    private static final boolean DEBUG = false;
    private static final String SBT_CONSOLE_TOOL_WINDOW_ID = "SBT Console";

    private SbtRunner sbt;
    private SbtConsole console;
    private final SbtProjectSettingsComponent projectSettings;
    private final SbtApplicationSettingsComponent applicationSettings;

    public static SbtRunnerComponent getInstance(Project project) {
        return project.getComponent(SbtRunnerComponent.class);
    }

    protected SbtRunnerComponent(Project project,
                                 SbtProjectSettingsComponent projectSettings,
                                 SbtApplicationSettingsComponent applicationSettings) {
        super(project);
        this.projectSettings = projectSettings;
        this.applicationSettings = applicationSettings;
        console = createConsole(project);
    }

    public CompletionSignal executeInBackground(final String action) {
        final CompletionSignal signal = new CompletionSignal();
        signal.begin();

        queue(new Task.Backgroundable(myProject, MessageBundle.message("sbt.tasks.executing"), false) {
            public void run(ProgressIndicator indicator) {
                try {
                    logger.info("Begin executing: " + action);
                    if (executeAndWait(action)) {
                        signal.success();
                        logger.info("Done executing: " + action);
                    } else {
                        logger.info("Error executing: " + action);
                    }
                } catch (IOException e) {
                    logger.error("Failed to execute action \"" + action + "\". Maybe SBT failed to start?", e);
                } finally {
                    signal.finished();
                }
            }
        });

        return signal;
    }

    @Override
    public void projectOpened() {
        final StartupManager manager = StartupManager.getInstance(myProject);
        manager.registerPostStartupActivity(new DumbAwareRunnable() {
            public void run() {
                registerToolWindow();
            }
        });
    }

    @Override
    public void disposeComponent() {
        unregisterToolWindow();
        destroyProcess();
    }

    public void recreateToolWindow() {
        unregisterToolWindow();
        console = createConsole(myProject);
        registerToolWindow();
    }

    private SbtConsole createConsole(Project project) {
        return new SbtConsole(MessageBundle.message("sbt.tasks.action"), project, this);
    }

    private void registerToolWindow() {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(myProject);
        if (toolWindowManager != null) {
            ToolWindow toolWindow =
                    toolWindowManager.registerToolWindow(SBT_CONSOLE_TOOL_WINDOW_ID, false, ToolWindowAnchor.BOTTOM, myProject, true);
            SbtRunnerComponent sbtRunnerComponent = SbtRunnerComponent.getInstance(myProject);
            sbtRunnerComponent.getConsole().ensureAttachedToToolWindow(toolWindow, false);
        }
    }

    private void unregisterToolWindow() {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(myProject);
        if (toolWindowManager != null && toolWindowManager.getToolWindow(SBT_CONSOLE_TOOL_WINDOW_ID) != null) {
          toolWindowManager.unregisterToolWindow(SBT_CONSOLE_TOOL_WINDOW_ID);
        }
    }

    private void queue(final Task.Backgroundable task) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            task.queue();
        } else {
            ApplicationManager.getApplication().invokeAndWait(new Runnable() {
                public void run() {
                    task.queue();
                }
            }, ModalityState.NON_MODAL);
        }
    }

    /**
     * @param action the SBT action to run
     * @return false if an error was detected, true otherwise
     * @throws IOException
     */
    public boolean executeAndWait(String action) throws IOException {
        saveAllDocuments();
        startIfNotStarted(true);
        boolean success;
        try {
            success = sbt.execute(action);

            // TODO: update target folders (?)
            // org.jetbrains.idea.maven.project.MavenProjectsManager#updateProjectFolders
            // org.jetbrains.idea.maven.execution.MavenRunner#runBatch
            // org.jetbrains.idea.maven.execution.MavenRunner#updateTargetFolders
        } catch (IOException e) {
            destroyProcess();
            throw e;
        }

        VirtualFileManager.getInstance().refreshWithoutFileWatcher(true);
        return success;
    }

    private static void saveAllDocuments() {
        ApplicationManager.getApplication().invokeAndWait(new Runnable() {
            public void run() {
                FileDocumentManager.getInstance().saveAllDocuments();
            }
        }, ModalityState.NON_MODAL);
    }

    public final SbtConsole getConsole() {
        return console;
    }

    public final void startIfNotStarted(boolean wait) throws IOException {
        if (!isSbtAlive()) {
            sbt = new SbtRunner(projectSettings.getJavaCommand(applicationSettings), projectDir(), launcherJar(), vmParameters());
            printToMessageWindow();
            if (DEBUG) {
                printToLogFile();
            }
            sbt.start(wait);
        }
    }

    public final boolean isSbtAlive() {
        return sbt != null && sbt.isAlive();
    }

    private File projectDir() {
        VirtualFile baseDir = myProject.getBaseDir();
        assert baseDir != null;
        return new File(baseDir.getPath());
    }

    private File launcherJar() {
        return new File(projectSettings.effectiveSbtLauncherJarPath(applicationSettings));
    }

    private String[] vmParameters() {
        String[] split = projectSettings.effectiveSbtLauncherVmParameters(applicationSettings).split("\\s");
        if (split.length == 1 && split[0].equals("")) return new String[0];
        else return split;
    }

    private void printToMessageWindow() {
        // org.jetbrains.idea.maven.execution.MavenExecutor#myConsole
        SbtProcessHandler process = new SbtProcessHandler(this, sbt.subscribeToOutput());
        console.attachToProcess(process, this);
        process.startNotify();
    }

    private void printToLogFile() {
        final OutputReader output = sbt.subscribeToOutput();
        Thread t = new Thread(new Runnable() {
            public void run() {
                Scanner scanner = new Scanner(output);
                while (scanner.hasNextLine()) {
                    logger.info(scanner.nextLine());
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void destroyProcess() {
        if (sbt != null) {
            sbt.destroy();
            sbt = null;
        }
    }
}
