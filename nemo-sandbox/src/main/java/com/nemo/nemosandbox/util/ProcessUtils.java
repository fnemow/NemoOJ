package com.nemo.nemosandbox.util;

import cn.hutool.core.util.StrUtil;
import com.nemo.nemosandbox.model.ExecuteMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.*;

/**
 * 进程工具类
 * 并发读取 stdout/stderr，避免管道写满导致死锁
 */
@Slf4j
public class ProcessUtils {

    /**
     * 执行进程——并发消费 stdout/stderr，然后等待退出
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String processState) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        try {
            // 并发读取 stdout 和 stderr（先消费再 waitFor，避免管道满死锁）
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread stdoutThread = readStreamAsync(runProcess.getInputStream(), stdout);
            Thread stderrThread = readStreamAsync(runProcess.getErrorStream(), stderr);

            // 等待进程退出
            long startTime = System.currentTimeMillis();
            int exitValue = runProcess.waitFor();
            long elapsed = System.currentTimeMillis() - startTime;

            // 等待 IO 线程完成读取
            stdoutThread.join(5000);
            stderrThread.join(5000);

            executeMessage.setExitValue(exitValue);
            executeMessage.setMessage(stdout.toString());
            executeMessage.setErrorMessage(stderr.toString());
            executeMessage.setTime(elapsed);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executeMessage.setExitValue(-1);
            executeMessage.setErrorMessage(processState + " 被中断");
        } catch (Exception e) {
            executeMessage.setExitValue(-1);
            executeMessage.setErrorMessage(processState + " 异常: " + e.getMessage());
            log.error("{} 异常", processState, e);
        }
        return executeMessage;
    }

    /**
     * 异步读取流（保留换行）
     */
    private static Thread readStreamAsync(InputStream inputStream, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append("\n");
                }
            } catch (IOException e) {
                target.append("[读取流异常: ").append(e.getMessage()).append("]");
            }
        }, "stream-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 交互式执行
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String args) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        try {
            OutputStream outputStream = runProcess.getOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            String[] arguments = args.split(" ");
            String join = StrUtil.join("\n", arguments) + "\n";
            outputStreamWriter.write(join);
            outputStreamWriter.flush();

            // 并发读取
            StringBuilder stdout = new StringBuilder();
            Thread stdoutThread = readStreamAsync(runProcess.getInputStream(), stdout);
            int exitValue = runProcess.waitFor();
            stdoutThread.join(5000);

            executeMessage.setExitValue(exitValue);
            executeMessage.setMessage(stdout.toString());

            outputStream.close();
            outputStreamWriter.close();
            runProcess.destroy();
        } catch (Exception e) {
            log.error("交互式执行异常", e);
            executeMessage.setErrorMessage(e.getMessage());
        }
        return executeMessage;
    }
}
