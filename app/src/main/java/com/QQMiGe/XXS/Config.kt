package com.QQMiGe.XXS

/** 全局常量 */
object Config {
    /**
     * QQ 小游戏数据目录。
     *
     * 注意：
     *  - /data/user/0 与 /data/data 互为符号链接别名，二者等价，换写法不解决权限问题
     *  - 结尾【不加】斜杠。`ls "/x/y/minigame/"` 在 trailing slash + 受限权限组合下
     *    会走不同错误分支（可能报 ENOENT 而非 EACCES），去掉更干净
     */
    const val MINIGAME_DIR = "/data/data/com.tencent.mobileqq/files/minigame"
}
