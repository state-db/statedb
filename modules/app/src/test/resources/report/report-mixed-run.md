### Pulumi `preview` — `shop-org/shop/dev`

**Succeeded**

**Resources:** `+1` create · `~2` update · `-1` delete · `±1` replace · `=1` unchanged

**Plan:**

```diff
-  aws:sns/topic:Topic legacy
@@ ±  aws:sqs/queue:Queue jobs  [fifoQueue (replaces)] @@
! ~  aws:rds/instance:Instance db  [instanceClass (replaces), password (secret), tags.env]
! ~  aws:elasticache/cluster:Cluster cache  [size]
+  aws:s3/bucket:Bucket assets  [acl]
```

**1 warning** — see the run log.

**[View full preview in state-db →](https://statedb.example.invalid/shop-org/shop/dev/previews/run)**
