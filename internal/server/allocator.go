package server

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
)

// VIPAllocator 虚拟 IP 分配器
// 约定：子网 .0 = network，.255 = broadcast，.1 保留给 server 自身（未来可选启用）
// 可分配范围 .2 ~ .254（对 /24 子网）
// 以 node_id 为 key 持久化 同一节点永远拿到相同 IP
type VIPAllocator struct {
	mu        sync.Mutex
	subnet    *net.IPNet
	firstIP   uint32 // 第一个可用
	lastIP    uint32 // 最后可用
	reserved  map[uint32]bool
	assigned  map[string]uint32 // nodeID -> ip (uint32)
	occupied  map[uint32]string // ip -> nodeID
	storeFile string
}

// NewVIPAllocator 创建 subnet 例如 "10.88.0.0/24"
// storeFile 为空则不持久化 (重启丢失分配记录)
func NewVIPAllocator(subnet, storeFile string) (*VIPAllocator, error) {
	_, n, err := net.ParseCIDR(subnet)
	if err != nil {
		return nil, fmt.Errorf("parse subnet %q: %w", subnet, err)
	}
	ip4 := n.IP.To4()
	if ip4 == nil {
		return nil, errors.New("only IPv4 subnet supported")
	}
	ones, bits := n.Mask.Size()
	if bits-ones < 2 {
		return nil, errors.New("subnet too small (need at least /30)")
	}
	network := binary.BigEndian.Uint32(ip4)
	broadcast := network | ((1 << (bits - ones)) - 1)

	a := &VIPAllocator{
		subnet:    n,
		firstIP:   network + 2, // 跳过 .0 (network) 和 .1 (server 保留)
		lastIP:    broadcast - 1,
		reserved:  map[uint32]bool{network + 1: true},
		assigned:  make(map[string]uint32),
		occupied:  make(map[uint32]string),
		storeFile: storeFile,
	}
	a.load()
	return a, nil
}

// Alloc 为 nodeID 分配 IP
//
//	已分配过 → 直接返回老 IP
//	hint 为具体 IP 且空闲 → 分配 hint
//	否则 → 找下一个空闲 IP
func (a *VIPAllocator) Alloc(nodeID, hint string) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()

	if v, ok := a.assigned[nodeID]; ok {
		return uintToIP(v).String(), nil
	}

	// hint 指定具体 IP 且在子网内且未占用
	if hint != "" && hint != "auto" {
		if hintIP := net.ParseIP(hint); hintIP != nil {
			if ip4 := hintIP.To4(); ip4 != nil && a.subnet.Contains(ip4) {
				v := binary.BigEndian.Uint32(ip4)
				if v >= a.firstIP && v <= a.lastIP && !a.reserved[v] && a.occupied[v] == "" {
					a.bind(nodeID, v)
					return hint, nil
				}
			}
		}
	}

	// 找下一个空闲
	for v := a.firstIP; v <= a.lastIP; v++ {
		if a.reserved[v] {
			continue
		}
		if _, busy := a.occupied[v]; busy {
			continue
		}
		a.bind(nodeID, v)
		return uintToIP(v).String(), nil
	}
	return "", errors.New("subnet exhausted")
}

func (a *VIPAllocator) bind(nodeID string, v uint32) {
	a.assigned[nodeID] = v
	a.occupied[v] = nodeID
	a.save()
}

// Release 手动释放某节点的 IP（当前未使用 保留给将来管理用）
func (a *VIPAllocator) Release(nodeID string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if v, ok := a.assigned[nodeID]; ok {
		delete(a.assigned, nodeID)
		delete(a.occupied, v)
		a.save()
	}
}

func (a *VIPAllocator) save() {
	if a.storeFile == "" {
		return
	}
	dump := make(map[string]string, len(a.assigned))
	for id, v := range a.assigned {
		dump[id] = uintToIP(v).String()
	}
	data, _ := json.MarshalIndent(dump, "", "  ")
	_ = os.MkdirAll(filepath.Dir(a.storeFile), 0755)
	_ = os.WriteFile(a.storeFile, data, 0600)
}

func (a *VIPAllocator) load() {
	if a.storeFile == "" {
		return
	}
	data, err := os.ReadFile(a.storeFile)
	if err != nil {
		return
	}
	var dump map[string]string
	if json.Unmarshal(data, &dump) != nil {
		return
	}
	for id, s := range dump {
		ip := net.ParseIP(s)
		if ip == nil {
			continue
		}
		ip4 := ip.To4()
		if ip4 == nil || !a.subnet.Contains(ip4) {
			continue
		}
		v := binary.BigEndian.Uint32(ip4)
		a.assigned[id] = v
		a.occupied[v] = id
	}
}

func uintToIP(v uint32) net.IP {
	b := make([]byte, 4)
	binary.BigEndian.PutUint32(b, v)
	return net.IP(b)
}
