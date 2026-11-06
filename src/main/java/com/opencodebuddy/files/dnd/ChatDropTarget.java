package com.opencodebuddy.files.dnd;

import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDTarget;
import com.intellij.ide.dnd.TransferableWrapper;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.opencodebuddy.files.HostBridge;
import org.jetbrains.annotations.NotNull;

import java.awt.Image;
import java.awt.Point;
import java.io.File;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE-internal drop target registered on the OpenCode chat browser component.
 *
 * <p>This is the portable mechanism that catches a drag-and-drop <em>from the
 * JetBrains Project view</em> (an IDE-internal DnD). The host plugin already
 * handles plain OS-file drops via its own AWT {@code DropTarget}; this target
 * complements it for the project-tree flavors. Files are routed to the chat
 * through {@link HostBridge#addFileReferences}.</p>
 */
public class ChatDropTarget implements DnDTarget {

    private static final Logger LOG = Logger.getInstance(ChatDropTarget.class);

    private final Project project;

    public ChatDropTarget(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public boolean update(@NotNull DnDEvent event) {
        try {
            // Only claim the drop when the IDE-internal payload actually carries files.
            return !extractPaths(event).isEmpty();
        } catch (Throwable t) {
            // Not an IDE-internal project-tree drag (e.g. plain OS file, or a non-file
            // payload); do not interfere.
            return false;
        }
    }

    @Override
    public void drop(@NotNull DnDEvent event) {
        List<String> paths;
        try {
            paths = extractPaths(event);
        } catch (Throwable t) {
            return;
        }
        if (!paths.isEmpty()) {
            LOG.info("[FilesPlugin] DnD: dropping " + paths.size() + " file(s) into chat");
            HostBridge.addFileReferences(project, paths);
        }
    }

    @Override
    public void cleanUpOnLeave() {
        // Nothing to clean up.
    }

    @Override
    public void updateDraggedImage(Image image, Point p1, Point p2) {
        // Non-interactive; kept for the interface contract.
    }

    private static List<String> extractPaths(@NotNull DnDEvent event) {
        List<String> out = new ArrayList<>();
        collect(event.getAttachedObject(), out);
        return out;
    }

    private static void collect(Object value, List<String> out) {
        if (value == null || out.size() > 512) {
            return;
        }
        // The canonical IDE-internal wrapper: gives VirtualFile[] directly.
        if (value instanceof TransferableWrapper wrapper) {
            VirtualFile[] files = wrapper.getVirtualFiles();
            if (files != null) {
                for (VirtualFile file : files) {
                    if (file != null && file.isValid()) {
                        out.add(file.getPath());
                    }
                }
            }
            return;
        }
        if (value instanceof VirtualFile file) {
            if (file.isValid()) {
                out.add(file.getPath());
            }
            return;
        }
        if (value instanceof File file) {
            out.add(file.getAbsolutePath());
            return;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len && out.size() <= 512; i++) {
                collect(Array.get(value, i), out);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (out.size() > 512) {
                    break;
                }
                collect(item, out);
            }
        }
    }
}
