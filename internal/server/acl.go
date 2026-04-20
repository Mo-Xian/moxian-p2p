package server

import "strings"

// aclAllows 判断 target 是否匹配 rules 中任一条件
// 规则形式：
//   - "node_id"    精确匹配 NodeID
//   - "tag=value"  匹配 target 的 Tags
//   - "*"          允许全部
// rules 为空 = 无限制（允许）
func aclAllows(rules []string, target *Session) bool {
	if len(rules) == 0 {
		return true
	}
	for _, r := range rules {
		r = strings.TrimSpace(r)
		if r == "" {
			continue
		}
		if r == "*" {
			return true
		}
		if strings.Contains(r, "=") {
			// 标签匹配
			for _, t := range target.Tags {
				if t == r {
					return true
				}
			}
			continue
		}
		if r == target.NodeID {
			return true
		}
	}
	return false
}
