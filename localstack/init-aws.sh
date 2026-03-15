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

echo "Queues created:"
awslocal sqs list-queues
