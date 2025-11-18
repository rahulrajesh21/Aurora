package com.musicstreaming.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseConfig {
    fun init() {
        // Get NeonDB connection string from environment variable
        val dbUrl = System.getenv("DATABASE_URL") 
            ?: "postgresql://username:password@ep-xyz.us-east-2.aws.neon.tech/neondb?sslmode=require"
        
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = dbUrl
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            idleTimeout = 600000
            connectionTimeout = 30000
            maxLifetime = 1800000
        }
        
        val dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource)
        
        // Create tables
        transaction {
            SchemaUtils.create(Rooms, RoomMembers, RoomQueue)
        }
    }
}
