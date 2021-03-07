docker run --rm --network=host -e ADDRESS=docker.for.mac.host.internal stor.highloadcup.ru/rally/solid_avocet


#### Graal 
```shell
docker build -t goldrush_graal -f Dockerfile-graal .

docker run --rm --network=host -e ADDRESS=docker.for.mac.host.internal goldrush_graal

docker tag goldrush_graal stor.highloadcup.ru/rally/solid_avocet
docker push stor.highloadcup.ru/rally/solid_avocet
```
