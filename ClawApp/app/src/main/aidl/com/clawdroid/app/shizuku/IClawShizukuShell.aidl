package com.clawdroid.app.shizuku;

interface IClawShizukuShell {
    void destroy() = 16777114;

    String exec(String command) = 1;
}
