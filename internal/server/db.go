package server

import (
	"database/sql"
	"fmt"
	"log"

	_ "modernc.org/sqlite"
)

// OpenDB 打开 SQLite 数据库 自动迁移 schema
// path="moxian.db" 或 ":memory:" 测试用
func OpenDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(on)")
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping sqlite: %w", err)
	}
	if err := migrate(db); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	log.Printf("[db] opened %s", path)
	return db, nil
}

// migrate 建表 幂等
func migrate(db *sql.DB) error {
	stmts := []string{
		// 用户表
		`CREATE TABLE IF NOT EXISTS users (
			id              INTEGER PRIMARY KEY AUTOINCREMENT,
			email           TEXT NOT NULL UNIQUE COLLATE NOCASE,
			username        TEXT NOT NULL UNIQUE,
			password_hash   TEXT NOT NULL,     -- Argon2id(clientPwdHash) 防暴破
			kdf_iterations  INTEGER NOT NULL DEFAULT 600000,
			is_admin        INTEGER NOT NULL DEFAULT 0,
			encrypted_vault TEXT,              -- "2.iv|ct|mac" 格式 客户端加密
			vault_version   INTEGER NOT NULL DEFAULT 0,
			created_at      INTEGER NOT NULL,
			updated_at      INTEGER NOT NULL
		)`,

		// 邀请码表
		`CREATE TABLE IF NOT EXISTS invites (
			code        TEXT PRIMARY KEY,
			created_by  INTEGER NOT NULL,       -- admin user id
			used_by     INTEGER,                 -- 注册后填
			used_at     INTEGER,
			expires_at  INTEGER NOT NULL,
			created_at  INTEGER NOT NULL,
			FOREIGN KEY (created_by) REFERENCES users(id),
			FOREIGN KEY (used_by) REFERENCES users(id)
		)`,

		// 节点表（用户的设备）
		`CREATE TABLE IF NOT EXISTS nodes (
			id          INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id     INTEGER NOT NULL,
			node_id     TEXT NOT NULL,           -- 如 "alice-phone"
			virtual_ip  TEXT NOT NULL,           -- 10.88.0.X
			tags        TEXT,                     -- JSON array 字符串
			description TEXT,
			created_at  INTEGER NOT NULL,
			FOREIGN KEY (user_id) REFERENCES users(id),
			UNIQUE (user_id, node_id)
		)`,

		// P2P 共享密码（每用户一个）
		// 用户所有节点用同一 passphrase 做 KCP AES 派生
		// 服务器生成（不是用户输入）节点登录后下发
		// 注：和 encrypted_vault 不同 这个服务器知道 属于 mesh-level 密钥
		`CREATE TABLE IF NOT EXISTS user_mesh_keys (
			user_id    INTEGER PRIMARY KEY,
			passphrase TEXT NOT NULL,
			FOREIGN KEY (user_id) REFERENCES users(id)
		)`,

		// ACL：哪些节点能连对方节点
		// 默认同用户内的节点可互连 未来支持跨用户 ACL
		`CREATE TABLE IF NOT EXISTS acls (
			id          INTEGER PRIMARY KEY AUTOINCREMENT,
			src_user_id INTEGER NOT NULL,
			dst_user_id INTEGER NOT NULL,
			created_at  INTEGER NOT NULL,
			FOREIGN KEY (src_user_id) REFERENCES users(id),
			FOREIGN KEY (dst_user_id) REFERENCES users(id),
			UNIQUE (src_user_id, dst_user_id)
		)`,

		// 索引
		`CREATE INDEX IF NOT EXISTS idx_invites_expires ON invites(expires_at) WHERE used_at IS NULL`,
		`CREATE INDEX IF NOT EXISTS idx_nodes_user ON nodes(user_id)`,
	}
	for i, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return fmt.Errorf("stmt %d: %w", i, err)
		}
	}
	return nil
}
