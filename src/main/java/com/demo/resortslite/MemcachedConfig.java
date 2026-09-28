package com.demo.resortslite;

import net.spy.memcached.MemcachedClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * FIXED cz-java-0070: AWS ElastiCache Memcached Configuration
 * 
 * This configuration replaces local in-memory caches with distributed caching
 * using Amazon ElastiCache for Memcached. This enables horizontal scaling
 * across multiple ECS Fargate tasks without cache inconsistency issues.
 * 
 * AWS Configuration Required:
 * 1. Create ElastiCache Memcached cluster in the same VPC as ECS tasks
 * 2. Configure security group to allow inbound traffic on port 11211 from ECS tasks
 * 3. Store Memcached endpoint in AWS SSM Parameter Store
 * 4. Inject endpoint into ECS task definition as MEMCACHED_ENDPOINT environment variable
 * 
 * Example ECS Task Definition (Terraform):
 * resource "aws_ecs_task_definition" "resorts_lite" {
 *   container_definitions = jsonencode([{
 *     environment = [
 *       {
 *         name  = "MEMCACHED_ENDPOINT"
 *         value = aws_elasticache_cluster.memcached.configuration_endpoint
 *       }
 *     ]
 *   }])
 * }
 * 
 * Example SSM Parameter Store:
 * aws ssm put-parameter \
 *   --name "/resorts-lite/memcached/endpoint" \
 *   --value "my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211" \
 *   --type "String"
 */
@Configuration
public class MemcachedConfig {

    @Value("${memcached.servers}")
    private String memcachedServers;

    @Bean
    public MemcachedClient memcachedClient() throws IOException {
        // Parse server address from configuration
        String[] parts = memcachedServers.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 11211;

        // Create Memcached client connected to AWS ElastiCache
        return new MemcachedClient(new InetSocketAddress(host, port));
    }
}
