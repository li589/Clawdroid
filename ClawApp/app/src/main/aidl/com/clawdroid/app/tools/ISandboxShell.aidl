package com.clawdroid.app.tools;

interface ISandboxShell {
    String exec(String command, long timeoutMs) = 1;
}
