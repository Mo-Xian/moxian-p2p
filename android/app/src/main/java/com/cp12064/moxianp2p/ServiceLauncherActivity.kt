package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * NAS 服务启动器：用户配置一批常用服务 URL（Jellyfin/Immich/Navidrome 等）
 * 点击唤起对应专业 APP 或浏览器打开 减少"装一堆 APP"的心智负担
 *
 * 数据存在 SharedPreferences "moxian" 的 "nas_services" 键（JSON 数组）
 * 见 NasService.kt
 */
class ServiceLauncherActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvWarnVpn: TextView
    private lateinit var btnAdd: Button
    private lateinit var btnImportTemplates: Button

    private val adapter = ServiceAdapter(
        onClick = { ServiceLauncher.open(this, it) },
        onMenu = { view, svc -> showItemMenu(view, svc) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service_launcher)

        rv = findViewById(R.id.rv_services)
        tvEmpty = findViewById(R.id.tv_empty)
        tvWarnVpn = findViewById(R.id.tv_warn_vpn)
        btnAdd = findViewById(R.id.btn_add)
        btnImportTemplates = findViewById(R.id.btn_import_templates)

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnAdd.setOnClickListener { showEditDialog(null) }
        btnImportTemplates.setOnClickListener { showImportTemplatesDialog() }

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "NAS 服务"
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        // 提示用户若 VPN 没起来 虚拟 IP 访问不了
        tvWarnVpn.visibility = if (ClientController.isRunning()) View.GONE else View.VISIBLE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        val items = NasServiceStore.load(this)
        adapter.submit(items)
        // 空列表显示提示 + 隐藏 RecyclerView
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        // 有数据后不再显示"一键导入"按钮 避免重复
        btnImportTemplates.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // 新建或编辑对话框 existing 为 null 时是新建
    private fun showEditDialog(existing: NasService?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_service_edit, null)
        val etName = view.findViewById<EditText>(R.id.et_name)
        val etUrl = view.findViewById<EditText>(R.id.et_url)
        val spType = view.findViewById<Spinner>(R.id.sp_type)

        val types = ServiceType.values()
        val typeLabels = types.map { "${it.emoji} ${it.label}" }
        spType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)

        existing?.let {
            etName.setText(it.name)
            etUrl.setText(it.url)
            spType.setSelection(types.indexOf(it.type).coerceAtLeast(0))
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加服务" else "编辑服务")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    toast("名称和 URL 必填")
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://") &&
                    !url.startsWith("smb://") && !url.startsWith("ftp://")) {
                    toast("URL 必须以 http:// https:// smb:// ftp:// 开头")
                    return@setPositiveButton
                }
                val type = types[spType.selectedItemPosition]
                if (existing == null) {
                    NasServiceStore.add(this, NasService(name = name, url = url, type = type))
                } else {
                    NasServiceStore.update(this, existing.copy(name = name, url = url, type = type))
                }
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 每一项的右侧三点菜单
    private fun showItemMenu(anchor: View, svc: NasService) {
        PopupMenu(this, anchor).apply {
            menu.add("打开")
            menu.add("编辑")
            menu.add("删除")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "打开" -> ServiceLauncher.open(this@ServiceLauncherActivity, svc)
                    "编辑" -> showEditDialog(svc)
                    "删除" -> confirmDelete(svc)
                }
                true
            }
        }.show()
    }

    private fun confirmDelete(svc: NasService) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确认删除\"${svc.name}\"？")
            .setPositiveButton("删除") { _, _ ->
                NasServiceStore.delete(this, svc.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 让用户勾选要导入哪些预设模板 基于虚拟 IP 10.88.0.2 批量创建
    private fun showImportTemplatesDialog() {
        val templates = ServiceTemplates.all.filter { it.name != "自定义 Web" }
        val labels = templates.map { "${it.type.emoji} ${it.name}  ${it.defaultUrl}" }.toTypedArray()
        val checked = BooleanArray(templates.size) { true }

        AlertDialog.Builder(this)
            .setTitle("选择要导入的服务")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("导入") { _, _ ->
                val existing = NasServiceStore.load(this)
                val toAdd = templates.filterIndexed { idx, _ -> checked[idx] }
                    .filter { tpl -> existing.none { it.name == tpl.name } }
                    .map { NasService(name = it.name, url = it.defaultUrl, type = it.type) }
                if (toAdd.isEmpty()) {
                    toast("没有需要新增的服务")
                    return@setPositiveButton
                }
                NasServiceStore.save(this, existing + toAdd)
                reload()
                toast("已导入 ${toAdd.size} 个服务 建议点\"编辑\"调整为你的实际 IP")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

// ---- RecyclerView Adapter ----
private class ServiceAdapter(
    val onClick: (NasService) -> Unit,
    val onMenu: (View, NasService) -> Unit,
) : RecyclerView.Adapter<ServiceAdapter.VH>() {

    private var items: List<NasService> = emptyList()

    fun submit(list: List<NasService>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nas_service, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val svc = items[position]
        h.tvIcon.text = svc.type.emoji
        h.tvName.text = svc.name
        h.tvUrl.text = svc.url
        h.itemView.setOnClickListener { onClick(svc) }
        h.btnMenu.setOnClickListener { onMenu(h.btnMenu, svc) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIcon: TextView = v.findViewById(R.id.tv_icon)
        val tvName: TextView = v.findViewById(R.id.tv_name)
        val tvUrl: TextView = v.findViewById(R.id.tv_url)
        val btnMenu: ImageButton = v.findViewById(R.id.btn_menu)
    }
}
