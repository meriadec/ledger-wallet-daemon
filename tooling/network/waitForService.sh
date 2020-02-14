#!/usr/bin/env bash

if [ ! $# -eq 2 ];
  then
    echo "ERROR : expect <hostname> and <port-number> as argument"
    exit 1
fi

hostname=$1
port=$2

for i in $(seq 1 100); do
  nc -z $hostname $port && echo Success && exit 0
  echo -n .
  sleep 1
done
echo Failed waiting for Postgres && exit 1
