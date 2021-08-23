# Github Watcher (name in progress)

## TODO
* Need to have a series of actions. Each action can have different effects based on the tags on the repo. For instance a `Kubernetes` tag can cause a PR environment (namespace) to be deployed.
- [ ] PR opened
- [ ] PR updated
- [ ] PR closed

- [ ] way to mark PR as do-not-deploy
- [ ] some general config file for what exactly needs to be deployed
- [ ] json logging
- [ ] reaper to clean up namespaces that have been left around (for stale PRs)

## Databases
- [ ] Need way to deploy ephemeral databases in the namespace
  - [ ] Postgres
  - [ ] Kafka (this one is going to be hard)
- [ ] Populate data in database
- [ ] Reset database on specific PR comment
