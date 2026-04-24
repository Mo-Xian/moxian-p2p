package com.cp12064.moxianp2p

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.request.ImageRequest

/**
 * 通用单图查看器（Immich 用；Jellyfin/Navidrome 海报复用）
 *
 * Intent extras:
 *   svc_id: String
 *   ids: ArrayList<String>
 *   start: Int  初始位置
 *   token: String  认证 token（走 Bearer）
 *   pathTpl: String  URL 模板 {id} 替换 默认 /api/asset/{id}/view
 */
class PhotoViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_view)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        val svc = NasService.findById(this, svcId) ?: run { finish(); return }
        val ids = intent.getStringArrayListExtra("ids") ?: emptyList<String>()
        val start = intent.getIntExtra("start", 0)
        val token = intent.getStringExtra("token") ?: ""
        val pathTpl = intent.getStringExtra("pathTpl") ?: "/api/asset/{id}/view"

        val vp = findViewById<ViewPager2>(R.id.vp_photos)
        val tvPos = findViewById<TextView>(R.id.tv_pos)

        vp.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val iv = ImageView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                return VH(iv)
            }
            override fun onBindViewHolder(h: VH, position: Int) {
                val id = ids[position]
                val url = svc.url + pathTpl.replace("{id}", id)
                h.iv.load(
                    ImageRequest.Builder(h.iv.context)
                        .data(url)
                        .addHeader("Authorization", "Bearer $token")
                        .crossfade(true)
                        .build()
                )
            }
            override fun getItemCount() = ids.size
        }
        vp.setCurrentItem(start, false)

        tvPos.text = "${start + 1} / ${ids.size}"
        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tvPos.text = "${position + 1} / ${ids.size}"
            }
        })
    }

    private class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)
}
