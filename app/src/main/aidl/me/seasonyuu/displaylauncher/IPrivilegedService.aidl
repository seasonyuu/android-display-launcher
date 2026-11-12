package me.seasonyuu.displaylauncher;
import android.os.Bundle;
import me.seasonyuu.displaylauncher.PreviewResult;
interface IPrivilegedService {
    Bundle launch(in Bundle request) = 0;
    PreviewResult capturePreview(int displayId) = 1;
    void destroy() = 16777114;
}
