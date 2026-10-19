package com.nemo.nemosandbox.security;

import java.security.Permission;

/**
 * 细粒度的权限检查
 *
 * @author Shier 2023/9/1 19:31
 */
public class MySecurityManager extends SecurityManager {

    /**
     * 检查所有权限（默认拒绝未明确允许的权限）
     */
    @Override
    public void checkPermission(Permission perm) {
        throw new SecurityException("权限异常：" + perm.toString());
    }

    /**
     * 禁止执行外部程序
     */
    @Override
    public void checkExec(String cmd) {
        throw new SecurityException("checkExec 权限异常：" + cmd);
    }

    /**
     * 仅允许读取 tmpCode 目录下的文件（编译和运行需要）
     */
    @Override
    public void checkRead(String file) {
        if (file != null && file.contains("tmpCode")) {
            return;
        }
        throw new SecurityException("checkRead 权限异常：" + file);
    }

    /**
     * 禁止写文件
     */
    @Override
    public void checkWrite(String file) {
        throw new SecurityException("checkWrite 权限异常：" + file);
    }

    /**
     * 禁止连接网络
     */
    @Override
    public void checkConnect(String host, int port) {
        throw new SecurityException("checkConnect 权限异常：" + host + ":" + port);
    }

    /**
     * 禁止删除文件
     */
    @Override
    public void checkDelete(String file) {
        throw new SecurityException("checkDelete 权限异常：" + file);
    }
}
