package com.example.test_agent.user;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final AtomicInteger counter = new AtomicInteger(1);

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Cacheable("users")                    // ครั้งแรก query DB แล้วเก็บใน Redis key="users"
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

    // เรียกจาก K8s CronJob ผ่าน POST /api/v1/users/batch/update-names
    @CacheEvict(value = "users", allEntries = true)
    public int updateAllFullNames() {
        int seq = counter.getAndIncrement();
        String suffix = String.format("%04d", seq); // "0001", "0002", ...
        List<User> users = userRepository.findAll();
        for (User user : users) {
            user.setFullName("xxxx" + suffix);
        }
        userRepository.saveAll(users);
        return seq;
    }
}
