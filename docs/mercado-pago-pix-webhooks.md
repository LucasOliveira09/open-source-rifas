# Mercado Pago: Pix com Payments e webhooks

Integração atualizada em 02/10/2026 para Checkout Transparente / Payments API.

## Criar o Pix

`POST /api/purchases` recebe nome, telefone e números. O backend reserva os números no PostgreSQL e calcula R$ 5,00 por número. O e-mail é lido de `PIX_BUYER_EMAIL`, sem solicitar e-mail no formulário nem enviar mensagens.

O backend envia `POST https://api.mercadopago.com/v1/payments` com `Authorization: Bearer <access-token>`, `Content-Type: application/json` e `X-Idempotency-Key: <UUID-da-compra>`. Exemplo ilustrativo:

```json
{
  "transaction_amount": 5.00,
  "description": "Rifa SEBRAE - 3º ano A e B",
  "payment_method_id": "pix",
  "external_reference": "<UUID-da-compra>",
  "date_of_expiration": "<data ISO 8601 com offset>",
  "payer": { "email": "<PIX_BUYER_EMAIL>" }
}
```

`transaction_amount` é um número decimal. `date_of_expiration` usa o prazo da reserva (padrão 24 horas; configuração de 30 minutos a 30 dias). Se `MERCADO_PAGO_NOTIFICATION_URL` estiver preenchida com uma URL HTTPS pública, ela também será enviada como `notification_url`. Se estiver vazia, configure a URL pelo painel do Mercado Pago.

O ID de pagamento retornado é numérico. O backend persiste o ID e os dados de `point_of_interaction.transaction_data`: `qr_code_base64`, `qr_code` e `ticket_url`. O contrato público `pix.qrCodeBase64`, `pix.copyPaste` e `pix.paymentUrl` permanece o mesmo, permitindo exibir o QR Code no próprio site.

Referências: [criação de pagamentos](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-payment/post) e [Pix com Payments](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-configuration/integrate-pix).

## Configurar e receber o webhook

1. Em **Suas integrações**, selecione a mesma aplicação das credenciais.
2. Em **Webhooks**, configure `https://<seu-dominio>/api/webhooks/mercadopago` no ambiente correspondente ao token.
3. Selecione **Pagamentos** (`payment`), substituindo a seleção anterior de Order.
4. Copie a **Assinatura secreta** para `MERCADO_PAGO_WEBHOOK_SECRET` e reinicie o backend se o valor mudar.

A URL precisa ser acessível pela internet com HTTPS. `localhost` e Tailscale Serve privado não recebem notificações reais do Mercado Pago.

O POST de webhook chega com query `data.id=<PAYMENT-ID>&type=payment`, headers `x-signature` e `x-request-id` e corpo semelhante a:

```json
{
  "id": 12345,
  "type": "payment",
  "action": "payment.updated",
  "data": { "id": "123456789" }
}
```

O SDK oficial valida HMAC-SHA256 usando a assinatura secreta. Sem assinatura válida, retorna 401 antes de consultar o provedor ou alterar o banco. Notificações assinadas de outros tópicos são ignoradas com 200.

Para `payment`, o backend consulta `GET https://api.mercadopago.com/v1/payments/{id}` usando o token privado, confere o ID retornado e usa `external_reference` para localizar a compra. O corpo do webhook não aprova uma compra sozinho. Veja a [documentação oficial de Webhooks](https://www.mercadopago.com.br/developers/pt/docs/links-and-debts/additional-content/your-integrations/notifications/webhooks).

## Confirmação no banco

- A compra e os números só passam a `PAID` após um webhook válido e consulta ao provedor: `status=approved`, `status_detail=accredited`, método `pix`, tipo `bank_transfer` e moeda `BRL`.
- `transaction_amount` e `transaction_details.total_paid_amount` devem corresponder ao total integral calculado no servidor.
- O ID precisa corresponder ao pagamento associado à compra. A coluna tem restrição de unicidade.
- Se o webhook chegar antes de a resposta de criação ser salva, ele pode associar o pagamento a uma compra pendente pelo UUID. A resposta de criação posterior só aceita o mesmo ID.
- A atualização de compra, números e evento é transacional. Eventos repetidos são deduplicados; compras pagas permanecem pagas.
- Pagamentos `cancelled`, `canceled`, `expired` ou `rejected` liberam números reservados. Apenas passar o prazo local não os libera.
- Em timeout ou resposta ambígua da criação, a reserva permanece, pois o pagamento pode ter sido criado. Recusas definitivas de criação liberam a reserva.
- A página consulta `GET /api/purchases/{id}` para refletir a confirmação, sem expor os dados pessoais do comprador.

## Migração do histórico de Orders

`V5__add_mercado_pago_payment_ids.sql` adiciona `mercado_pago_payment_id` à compra e ao evento, mantendo as colunas de Orders e o histórico. IDs `ORD...` não são copiados para IDs de Payments. O código impede associar um Payment a uma compra que já tenha um ID de Order.

O receptor atual processa somente `payment`. Antes de aplicar esta versão em um ambiente com Orders pendentes, essas cobranças precisam ser reconciliadas/canceladas na integração anterior; preservar o histórico não processa novos webhooks de Orders. Localmente, os 100 números estavam disponíveis antes da troca.

Para voltar ao código anterior, as novas colunas podem permanecer no banco; os registros de Payments precisam ser reconciliados antes de voltar a processar apenas Orders. A migração Flyway já aplicada não deve ser editada nem removida.

## Diagnóstico e limites

Antes da migração, o token atualizado foi aceito por `GET /users/me` e `GET /v1/payments/search` (200). `POST /v1/orders` com corpo vazio deliberadamente inválido retornou 403, código `PA_UNAUTHORIZED_RESULT_FROM_POLICIES`. Esse diagnóstico não criou cobrança e não prova permissão de criação em Payments. A mudança foi solicitada pelo usuário; a escolha da API não comprova maior segurança nem garante que uma cobrança será aceita.

Os logs de recusa incluem somente status HTTP e UUID da compra, sem token, e-mail ou corpo da resposta. O cliente usa timeout de conexão de 3 segundos e leitura de 12 segundos.

O webhook consulta o provedor e grava no banco antes de devolver 200. Uma consulta que falhe retorna 502 para permitir retentativa. Para responder antes desse processamento sem perder eventos seria necessária uma fila durável. A geração de uma cobrança real, a transferência Pix e a entrega pública do webhook ainda precisam ser confirmadas com a configuração do ambiente; compilar e iniciar a API não validam esse ciclo completo.
