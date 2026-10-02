# Mercado Pago: Pix e webhooks

Estudo da documentação oficial e comparação com o código em 02/10/2026.

## Rotas da aplicação

| Rota local | Responsabilidade |
| --- | --- |
| `POST /api/purchases` | Receber nome, telefone e números, reservar no PostgreSQL e iniciar o Pix |
| `GET /api/purchases/{id}` | Consultar a compra persistida para atualizar a tela |
| `POST /api/webhooks/mercadopago` | Validar notificações e atualizar a compra e os números |

O projeto utiliza Checkout Transparente com Orders API. O preço vem do banco: R$ 5,00 por número. `external_reference` e a chave de idempotência usam o UUID da compra, enquanto o e-mail vem de `PIX_BUYER_EMAIL` no `.env`.

## Criar o Pix

O backend envia `POST https://api.mercadopago.com/v1/orders`, com `Authorization: Bearer <access-token>`, `Content-Type: application/json` e `X-Idempotency-Key: <UUID-da-compra>`. Valor e soma das transações devem corresponder. Veja a [referência de criação de orders](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api/create-order/post).

Exemplo para um número:

```json
{
  "type": "online",
  "total_amount": "5.00",
  "external_reference": "<UUID-da-compra>",
  "processing_mode": "automatic",
  "transactions": {
    "payments": [{
      "amount": "5.00",
      "payment_method": { "id": "pix", "type": "bank_transfer" },
      "expiration_time": "PT24H"
    }]
  },
  "payer": { "email": "<PIX_BUYER_EMAIL>" }
}
```

O retorno inclui o identificador da order. Os dados de pagamento ficam em `transactions.payments[0].payment_method`: `qr_code`, `qr_code_base64` e `ticket_url`. Um Pix aguardando transferência normalmente tem `action_required`/`waiting_transfer`. A geração pode ser assíncrona; uma atualização posterior pode trazer o QR Code. Veja o [guia oficial de Pix](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-integration/websites/pix).

## Receber o webhook

Cadastre uma URL HTTPS acessível ao Mercado Pago terminando em `/api/webhooks/mercadopago`, selecione **Order (Mercado Pago)** no painel e salve o segredo gerado em `MERCADO_PAGO_WEBHOOK_SECRET`. O POST chega com query `data.id=<ORDER-ID>&type=order`, corpo JSON e headers `x-signature` e `x-request-id`. A confirmação HTTP deve chegar em até 22 segundos; o provedor repete entregas sem confirmação. Veja a [documentação de notificações de Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/notifications).

O handler atual passa assinatura, request ID, data ID e segredo ao validador do SDK Java. O [código oficial do validador](https://github.com/mercadopago/sdk-java/blob/master/src/main/java/com/mercadopago/webhook/WebhookSignatureValidator.java) calcula HMAC-SHA256 sobre `id:<data.id>;request-id:<x-request-id>;ts:<ts>;` e compara o resultado com `v1`. A normalização remove espaços externos e preserva a capitalização do ID.

Após validar a origem, consulte `GET https://api.mercadopago.com/v1/orders/{id}` com o token privado para obter o estado da order. Veja a [referência de consulta](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api/get-order/get).

## Regras implementadas no projeto

- A criação reserva os números e calcula o total no servidor.
- O segredo e o token são necessários para habilitar o checkout.
- O webhook consulta a order e usa seu `external_reference` para localizar a compra; o estado recebido no corpo não aprova a compra sozinho.
- A confirmação exige order e pagamento `processed`/`accredited`, método `pix` e `total_paid_amount` correspondente ao total integral. Uma moeda informada deve ser BRL.
- Compra e números passam a PAID em uma transação. Eventos repetidos são tratados com idempotência; compras já pagas permanecem pagas.
- Cancelamento, expiração ou falha confirmados pelo provedor liberam os números. Um timeout local de criação mantém a reserva, pois pode haver uma order criada sem resposta recebida.
- O frontend consulta o estado no banco a cada cinco segundos e aguarda o webhook para refletir a aprovação.

## Pontos de atenção encontrados

1. O handler atual consulta o provedor e atualiza o banco antes de devolver 200. O cliente HTTP não tem timeouts explícitos: uma demora do provedor ou do banco pode ultrapassar o prazo. Para responder antes do processamento sem perder eventos, é necessário persistir a notificação numa fila durável e processá-la com retentativas; apenas disparar uma tarefa em memória não garante a entrega.
2. Falta validar uma entrega real pela internet com a URL pública, o segredo correto e a order correspondente. Uma simulação no painel com ID inexistente não confirma um pagamento.
3. O teste local anterior foi recusado pelo provedor. Depois da troca de credenciais, a geração real e a confirmação ainda precisam ser verificadas novamente.
4. Um e-mail fictício é uma configuração da aplicação; sua aceitação depende das regras do provedor. Em sandbox, o domínio precisa ser `@testuser.com`, conforme a [referência de criação](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api/create-order/post).

O estudo não equivale a uma validação de pagamento real. O backend foi recompilado e reiniciado após mover o e-mail para o `.env`; não houve nova cobrança durante este estudo.
