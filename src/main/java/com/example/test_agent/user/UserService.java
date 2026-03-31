package com.example.test_agent.user;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Cacheable("users")                    // ครั้งแรก query DB แล้วเก็บใน Redis key="users"
                                           // ครั้งต่อไป Spring ดึงจาก Redis เลย ไม่ query DB
    public List<User> getAll() {
        return userRepository.findAll();
    }

    @Cacheable(value = "users", key = "#id")  // cache แยกตาม id เช่น key="users::1"
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @CacheEvict(value = "users", allEntries = true)  // ล้าง cache ทั้งหมดเมื่อข้อมูลเปลี่ยน
    public void evictCache() {
    }
}
