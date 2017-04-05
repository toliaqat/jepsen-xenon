# jepsen.xenon

Jepsen tests for Xenon

 * Xenon: https://github.com/vmware/xenon
 * Jespen: https://github.com/jepsen-io/jepsen

## Requirements

 * docker
 * docker-compose

## Testing

Simply run following commands to start a cluster for jepsen testing.

```
cd docker
./up.sh
```

On completion of above command you will get following message.

```
jepsen-control | Welcome to Jepsen on Docker
jepsen-control | ===========================
jepsen-control |
jepsen-control | Please run `docker exec -it jepsen-control bash` in another terminal to proceed.
```

After running `docker exec -it jepsen-control bash` on another terminal, run following command to start the tests.

```
lein run test --time-limit 60
```

## TODO

 * Fix hardcoded xenon jar url
 * Add concurrent multi-key test support
 * Add model/checker for query/read verification
 * Add delete and update operations
 * Add specialized partitioning schemes with nemesis
 * Add checker for eventual consistency on queries
 * Add clock related tests
 * Add tests that break the system

## Credits

Kyle Kingsbury: https://github.com/aphyr

## License

Copyright © 2017 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
