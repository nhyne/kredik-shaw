# Github Watcher (name in progress)

### TODO
* Need to have a series of actions. Each action can have different effects based on the tags on the repo. For instance a `Kubernetes` tag can cause a PR environment (namespace) to be deployed.
- [ ] PR opened
- [ ] PR updated
- [ ] PR closed

* Eventually going to need something to template the manifests and/or build the docker image for the service
- [ ] going to need some way to determine if an image needs to be built (just the presence of a dockerfile?)

- [ ] way to mark PR as do-not-deploy
