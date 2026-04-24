package com.cp12064.moxianp2p

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解锁页：JWT 还有效但 masterKey 丢了（进程重启 / 从后台恢复过久）
 * 用户只需重输主密码 不用重新注册 / 重新跑 KDF 所有参数
 */
class UnlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)

        findViewById<TextView>(R.id.tv_user).text =
            "${AuthSession.getUsername()} · ${AuthSession.getEmail()}"

        val etPwd = findViewById<EditText>(R.id.et_password)
        val btnUnlock = findViewById<Button>(R.id.btn_unlock)
        val btnLogout = findViewById<Button>(R.id.btn_logout)
        val pb = findViewById<ProgressBar>(R.id.pb_loading)
        val err = findViewById<TextView>(R.id.tv_error)

        btnUnlock.setOnClickListener {
            val pwd = etPwd.text.toString()
            if (pwd.isEmpty()) { err.text = "输入主密码"; return@setOnClickListener }
            btnUnlock.isEnabled = false
            pb.visibility = View.VISIBLE
            err.text = ""
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    AuthSession.unlockWithPassword(this@UnlockActivity, pwd)
                }
                pb.visibility = View.GONE
                btnUnlock.isEnabled = true
                if (ok) {
                    startActivity(Intent(this@UnlockActivity, MainActivity::class.java))
                    finish()
                } else {
                    err.text = "主密码错误 或 JWT 已过期"
                }
            }
        }

        btnLogout.setOnClickListener {
            AuthSession.logout(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
