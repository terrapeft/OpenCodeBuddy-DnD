package com.opencodebuddy.files.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.opencodebuddy.files.HostBridge;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Solution-explorer / project-view / editor popup action: "Add to OpenCode Context".
 *
 * <p>Collects the selected files (resolved from {@link VirtualFile} or {@link PsiElement}
 * data keys — Rider's Solution Explorer exposes one or the other) and routes their
 * absolute paths into the active OpenCode Buddy chat window through the reflection bridge.</p>
 */
public class AddToOpenCodeContextAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(AddToOpenCodeContextAction.class);

    private static boolean loggedConstructed = false;
    private static String lastVisibleState = null;

    public AddToOpenCodeContextAction() {
        super("Add to OpenCode Context", "Add the selected files to the OpenCode chat context",
                com.intellij.icons.AllIcons.General.Add);
        if (!loggedConstructed) {
            loggedConstructed = true;
            LOG.info("[FilesPlugin] AddToOpenCodeContextAction constructed");
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        Project project = e.getProject();
        VirtualFile[] files = project == null ? null : getSelectedFiles(e);
        boolean hasFiles = files != null && files.length > 0;
        // Show whenever the host is reachable so a missing file is not silently swallowed;
        // only enable when a usable selection is present.
        boolean hostPresent = project != null && HostBridge.isHostPresent(project);
        presentation.setVisible(hostPresent);
        presentation.setEnabled(hasFiles);

        // One-time diagnostic on the first meaningful transition.
        String state = (hostPresent ? "host" : "noHost") + "/" + (hasFiles ? "files" : "noFiles");
        if (!state.equals(lastVisibleState)) {
            lastVisibleState = state;
            LOG.info("[FilesPlugin] action update -> " + state
                    + (hasFiles ? " (selected=" + files.length + ")" : ""));
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            LOG.warn("[FilesPlugin] actionPerformed: no project");
            return;
        }
        VirtualFile[] files = getSelectedFiles(e);
        if (files == null || files.length == 0) {
            LOG.warn("[FilesPlugin] actionPerformed: no files selected");
            return;
        }
        List<String> paths = new ArrayList<>();
        for (VirtualFile file : files) {
            if (file != null && file.isValid()) {
                paths.add(file.getPath());
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        LOG.info("[FilesPlugin] Add to OpenCode Context: " + paths.size() + " file(s)");
        HostBridge.addFileReferences(project, paths);
        // Best-effort: make sure the IDE-internal drop target is registered too.
        com.opencodebuddy.files.dnd.DndRegistrar.ensureRegistered(project);
    }

    @NotNull
    private static VirtualFile[] getSelectedFiles(@NotNull AnActionEvent e) {
        // Multi-selection from the project / solution view.
        VirtualFile[] files = e.getData(LangDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null && files.length > 0) {
            return files;
        }
        files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null && files.length > 0) {
            return files;
        }
        // Single file from the editor / breadcrumbs, etc.
        VirtualFile single = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (single != null) {
            return new VirtualFile[]{single};
        }
        // Rider's Solution Explorer may surface the selection as PSI instead of VirtualFile.
        PsiElement[] psiArray = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
        if (psiArray != null && psiArray.length > 0) {
            List<VirtualFile> out = new ArrayList<>();
            for (PsiElement psi : psiArray) {
                VirtualFile f = virtualFileOf(psi);
                if (f != null) {
                    out.add(f);
                }
            }
            return out.isEmpty() ? null : out.toArray(new VirtualFile[0]);
        }
        PsiElement psi = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (psi != null) {
            VirtualFile f = virtualFileOf(psi);
            return f != null ? new VirtualFile[]{f} : null;
        }
        return null;
    }

    private static VirtualFile virtualFileOf(PsiElement psi) {
        if (psi instanceof PsiFile psiFile) {
            return psiFile.getVirtualFile();
        }
        if (psi instanceof PsiDirectory psiDirectory) {
            return psiDirectory.getVirtualFile();
        }
        PsiFile containingFile = psi.getContainingFile();
        return containingFile == null ? null : containingFile.getVirtualFile();
    }
}
