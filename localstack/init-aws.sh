#!/bin/bash
echo "Creating SQS FIFO queues..."

awslocal sqs create-queue \
  --queue-name skytrack-positions.fifo \
  --attributes '{
    "FifoQueue": "true",
    "ContentBasedDeduplication": "true",
    "VisibilityTimeout": "30"
  }'

awslocal sqs create-queue \
  --queue-name skytrack-airport-events.fifo \
  --attributes '{
    "FifoQueue": "true",
    "ContentBasedDeduplication": "true",
    "VisibilityTimeout": "30"
  }'

awslocal sqs create-queue \
  --queue-name skytrack-predictions.fifo \
  --attributes '{
    "FifoQueue": "true",
    "ContentBasedDeduplication": "true",
    "VisibilityTimeout": "30"
  }'

echo "Queues created:"
awslocal sqs list-queues

echo "Creating DynamoDB table..."

awslocal dynamodb create-table \
  --table-name skytrack-aircraft \
  --attribute-definitions \
    AttributeName=icao24,AttributeType=S \
    AttributeName=sortKey,AttributeType=S \
  --key-schema \
    AttributeName=icao24,KeyType=HASH \
    AttributeName=sortKey,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

awslocal dynamodb update-time-to-live \
  --table-name skytrack-aircraft \
  --time-to-live-specification "Enabled=true,AttributeName=ttl"

echo "DynamoDB table created:"
awslocal dynamodb list-tables

echo "Creating S3 history bucket..."

awslocal s3 mb s3://skytrack-history

echo "S3 buckets created:"
awslocal s3 ls
