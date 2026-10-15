package com.mychecklist.user.data

import com.mychecklist.user.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {

    fun findByFirebaseUid(firebaseUid: String): User?

    fun findByEmail(email: String): User?

    /**
     * Creates the user row if it doesn't exist yet. Safe when two requests for a
     * brand-new user arrive at the same moment (no duplicate-key errors).
     */
    @Modifying
    @Query(
        value = "INSERT INTO users (id, firebase_uid, email) VALUES (:id, :uid, :email) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun insertIfAbsent(@Param("id") id: UUID, @Param("uid") uid: String, @Param("email") email: String): Int
}
