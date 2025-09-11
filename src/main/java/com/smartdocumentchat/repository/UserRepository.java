package com.smartdocumentchat.repository;

import com.smartdocumentchat.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * מציאת משתמש לפי שם משתמש
     */
    Optional<User> findByUsername(String username);

    /**
     * מציאת משתמש לפי אימייל
     */
    Optional<User> findByEmail(String email);

    /**
     * מציאת משתמש לפי שם משתמש או אימייל
     */
    @Query("SELECT u FROM User u WHERE u.username = :identifier OR u.email = :identifier")
    Optional<User> findByUsernameOrEmail(@Param("identifier") String identifier);

    /**
     * בדיקה אם שם משתמש קיים
     */
    boolean existsByUsername(String username);

    /**
     * בדיקה אם אימייל קיים
     */
    boolean existsByEmail(String email);

    /**
     * מציאת כל המשתמשים הפעילים
     */
    List<User> findByActiveTrue();

    /**
     * חיפוש משתמשים לפי שם מלא
     */
    @Query("SELECT u FROM User u WHERE " +
            "LOWER(CONCAT(COALESCE(u.firstName, ''), ' ', COALESCE(u.lastName, ''))) " +
            "LIKE LOWER(CONCAT('%', :name, '%'))")
    List<User> findByFullNameContaining(@Param("name") String name);
}