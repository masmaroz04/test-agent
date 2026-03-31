package com.example.test_agent.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {  // Redis serialize object เป็น bytes ต้องการ Serializable

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    private String email;
}
