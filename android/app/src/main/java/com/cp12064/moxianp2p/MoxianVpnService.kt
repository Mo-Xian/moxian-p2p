package com.cp12064.moxianp2p

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * 基于 Android VpnService 的 TUN 实现
 * 工作流程：
 *   Builder.addAddress(vip, 24) + addRoute(10.88.0.0, 24) + establish() → tun fd
 *   把 fd 传给 Go 侧（ClientController.start），Go 代码通过 wireguard/tun.CreateFromFD 复用
 *
 * 注意：VpnService 在 establish() 后 Android 自动将其视为前台服务 不需要 startForeground()
 * 系统会显示"已激活 VPN"通知 + 状态栏图标 作为可见入口
 */
class MoxianVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val yamlCfg = intent?.getStringExtra(EXTRA_YAML)
        val vip = intent?.getStringExtra(EXTRA_VIP)
        if (yamlCfg.isNullOrBlank() || vip.isNullOrBlank()) {
            ClientController.appendLog("[service] missing yaml or vip, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val fd = try {
            buildVpn(vip)
        } catch (e: Exception) {
            ClientController.appendLog("[service] VpnService.Builder failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } ?: run {
            ClientController.appendLog("[service] establish() returned null (没有 VPN 授权?)")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ClientController.start(this, yamlCfg, fd)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildVpn(vip: String): Int? {
        val builder = Builder()
            .setSession("moxian-p2p")
            .setMtu(1400)
            .addAddress(vip, 24)
            .addRoute("10.88.0.0", 24)

        // 防止本 APP 自己的流量被路由回 TUN 形成死循环
        // 如果不加，moxian-client 的信令/STUN UDP 包会被自己的 VPN 吞了
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }

        val pfd = builder.establish() ?: return null
        return pfd.detachFd()
    }

    override fun onDestroy() {
        ClientController.stop()
        super.onDestroy()
    }

    override fun onRevoke() {
        // 用户手动撤销 VPN 授权
        ClientController.stop()
        stopSelf()
        super.onRevoke()
    }

    companion object {
        const val EXTRA_YAML = "yaml"
        const val EXTRA_VIP = "vip"

        fun buildStartIntent(ctx: Context, yaml: String, vip: String): Intent =
            Intent(ctx, MoxianVpnService::class.java).apply {
                putExtra(EXTRA_YAML, yaml)
                putExtra(EXTRA_VIP, vip)
            }
    }
}
