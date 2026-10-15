package com.mychecklist.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Column(name = "firebase_uid", nullable = false, unique = true, length = 128)
    var firebaseUid: String,

    @Column(nullable = false, unique = true, length = 320)
    var email: String,

    @Column(name = "display_name", length = 200)
    var displayName: String? = null,

    @Column(name = "photo_url")
    var photoUrl: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_role", nullable = false, length = 20)
    var platformRole: PlatformRole = PlatformRole.USER,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    val isSuperAdmin: Boolean get() = platformRole == PlatformRole.SUPER_ADMIN
}
