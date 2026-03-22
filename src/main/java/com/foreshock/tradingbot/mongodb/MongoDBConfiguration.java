package com.foreshock.tradingbot.mongodb;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Configuration class for MongoDB
 */
@Configuration
@EnableMongoRepositories(basePackages = "com.foreshock.tradingbot.mongodb")
public class MongoDBConfiguration {

}
