guardrail-sample-http4s-zio
===

A simple project to show what a guardrail+http4s+zio service could look like.

Some sample curls:

#### getInventory
```bash
curl localhost:8080/api/v3/store/inventory | jq .
```

#### getOrderById
```bash
curl localhost:8080/api/v3/store/order/123 | jq .
curl localhost:8080/api/v3/store/order/124 # 404
```

#### placeOrder
```bash
curl localhost:8080/api/v3/store/order \
        -H 'Content-Type: application/json' \
        --data '{"id": 124, "petId": 44, "quantity": 10}' \
        | jq .
```
