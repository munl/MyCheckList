package com.mychecklist

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MyChecklistApplication

fun main(args: Array<String>) {
    runApplication<MyChecklistApplication>(*args)
}
