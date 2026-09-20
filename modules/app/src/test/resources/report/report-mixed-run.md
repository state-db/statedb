### Pulumi `preview` — `shop-org/shop/dev`

**+1 ~2 -1 ±1 =1** · `succeeded`

| | Resource | Changed properties |
| --- | --- | --- |
| `+` | `aws:s3/bucket:Bucket` `assets` | `acl` |
| `~` | `aws:rds/instance:Instance` `db` | `instanceClass` (replaces), `password` (secret), `tags.env` |
| `~` | `aws:elasticache/cluster:Cluster` `cache` | `size` |
| `-` | `aws:sns/topic:Topic` `legacy` | — |
| `±` | `aws:sqs/queue:Queue` `jobs` | `fifoQueue` (replaces) |

**1 warning** — see the run log.

[View this run in statedb](https://statedb.example.invalid/shop-org/shop/dev/previews/run)
