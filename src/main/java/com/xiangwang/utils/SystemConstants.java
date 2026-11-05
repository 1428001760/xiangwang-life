package com.xiangwang.utils;

public class SystemConstants {

    /**
     * 图片上传目录。
     * 默认取当前工作目录下的 imgs/，可通过环境变量 APP_UPLOAD_IMAGE_DIR 覆盖，
     * 避免把某台机器的绝对路径写死在代码里。
     */
    public static final String IMAGE_UPLOAD_DIR = resolveUploadDir();

    private static String resolveUploadDir() {
        String dir = System.getenv("APP_UPLOAD_IMAGE_DIR");
        if (dir == null || dir.trim().isEmpty()) {
            dir = "./imgs/";
        }
        if (!dir.endsWith("/") && !dir.endsWith("\\")) {
            dir = dir + "/";
        }
        return dir;
    }

    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
