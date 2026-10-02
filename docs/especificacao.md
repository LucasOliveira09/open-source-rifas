# Especificação inicial — Rifas Iracema

## Objetivo

Permitir que participantes vejam os prêmios e os 100 números de uma rifa da escola Iracema, escolham números disponíveis, informem seus dados e paguem pelo Mercado Pago.

## Stack

- Frontend: Angular 21.
- Backend: Java 21 e Spring Boot 4.1.1.
- Banco de dados: PostgreSQL.
- Pagamentos: Pix direto pelo Checkout Transparente / Orders API do Mercado Pago, com confirmação via Webhook.

## Fluxo de compra

1. O frontend consulta a rifa fixa, seus prêmios e a disponibilidade dos 100 números na API.
2. O comprador escolhe um ou mais números disponíveis e informa nome e telefone. O backend utiliza o e-mail fictício configurado em `PIX_BUYER_EMAIL` no `.env` para a integração com o Mercado Pago (padrão `rifas@example.com`; domínio `@testuser.com` para sandbox).
3. O backend valida os dados e reserva os números em uma transação no PostgreSQL. A reserva impede que duas compras obtenham o mesmo número enquanto o Pix estiver pendente.
4. O backend calcula o preço usando os dados cadastrados no servidor e cria uma order Pix com chave de idempotência. Nenhum token privado é enviado ao navegador.
5. O backend retorna ao frontend o QR Code e o código Pix Copia e Cola; o frontend os exibe enquanto consulta o estado da compra.
6. O Mercado Pago envia um Webhook. O backend valida a assinatura e consulta a order na API do Mercado Pago. Em uma transação, só marca compra e números como pagos se a order e o pagamento estiverem `processed`/`accredited`, o método for Pix e o valor integral corresponder ao total da compra.
7. Orders canceladas, expiradas ou falhas liberam a reserva quando o estado final é confirmado pelo Mercado Pago. A aplicação não libera números apenas pelo próprio relógio, pois uma confirmação Pix pode chegar com atraso.

O redirecionamento de retorno do navegador serve para exibir o resultado ao comprador; não confirma pagamento.

## Modelo de dados inicial

- `raffle`: título, descrição, status e preço unitário em centavos de BRL. A primeira versão tem uma rifa fixa.
- `prize`: rifa, descrição, ordem e imagem opcional.
- `raffle_number`: rifa, número de 1 a 100 e status (`AVAILABLE`, `RESERVED`, `PAID`). Deve haver uma restrição única por rifa e número.
- `purchase`: nome e telefone do comprador, e-mail fictício configurado no backend, status, total em centavos, referência externa e datas.
- `purchase_number`: histórico de números vinculados a tentativas de compra; números de tentativas não pagas podem ser associados a outra compra.
- `payment_event`: identificador do evento/pagamento Mercado Pago para auditoria e idempotência.

Não armazenar dados de cartão. O token privado do Mercado Pago e o segredo de Webhook ficam somente em variáveis de ambiente do backend.

## Contrato HTTP proposto

- `GET /api/raffle`: exibir detalhes e prêmios da rifa fixa.
- `GET /api/raffle/numbers`: listar os 100 números e sua disponibilidade sem dados pessoais.
- `POST /api/purchases`: receber `name`, `phone` e `numbers`, validar comprador e números, registrar a compra e iniciar o checkout. O e-mail fictício é lido da configuração do servidor.
- `GET /api/purchases/{purchaseId}`: consultar status, números e dados Pix da própria compra, sem retornar os dados pessoais do comprador.
- `POST /api/webhooks/mercadopago`: receber e validar notificações do Mercado Pago.

Compra com número indisponível retorna `409 Conflict`. Erros seguem um formato consistente com código e mensagem; dados internos e credenciais nunca são retornados.

## Frontend

- Página pública mostra os prêmios, o preço e a grade de 100 números.
- Números disponíveis podem ser selecionados; reservados ou pagos não podem.
- Formulário coleta nome e telefone antes de iniciar o checkout.
- Após a API criar o checkout, o navegador segue para a URL do Mercado Pago.
- A tela de retorno consulta o status da compra à API; não confia nos parâmetros de retorno para declarar pagamento aprovado.

## Critérios de sucesso do MVP

- A API persiste rifa, prêmios, números, compras e pagamentos em PostgreSQL.
- Cada rifa possui exatamente 100 números identificados de 1 a 100.
- Números reservados não podem ser comprados por outra pessoa; números pagos são permanentes.
- O valor enviado ao Mercado Pago é calculado pelo backend.
- Uma notificação inválida não altera o status da compra.
- Uma notificação repetida não duplica pagamentos ou libera números pagos.
- O Webhook confirma o valor e o método antes de marcar os números como pagos.
- O frontend apresenta os estados de disponibilidade e encaminha o comprador ao checkout.

## Decisões pendentes

- Por quanto tempo um número fica reservado enquanto o pagamento está pendente?
- A escola escolherá manualmente o número sorteado ou usará um resultado externo (por exemplo, Loteria Federal)?
- Quem poderá cadastrar/alterar prêmios e acompanhar as compras? O painel administrativo fica fora do primeiro fluxo público até essa regra ser definida.
- O preço unitário da rifa Iracema é R$ 5,00 (500 centavos de BRL).

## Referências oficiais

- [Mercado Pago — Pix com Checkout Transparente e Orders API](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-integration/pix)
- [Mercado Pago — notificações Webhook e validação da assinatura](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-orders/notifications?scope=prod)
- [Mercado Pago — comparação de APIs de Checkout Pro](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-orders/overview)
- [Spring Boot — configuração JDBC, JPA e repositórios](https://docs.spring.io/spring-boot/how-to/data-access.html)
- [Spring Boot — migrações Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Angular — configuração de HttpClient](https://angular.dev/guide/http/setup)
- [Angular — formulários reativos](https://angular.dev/guide/forms/reactive-forms)

## Comandos

- Frontend: `cd frontend; npm start`
- Backend: `cd backend; .\mvnw.cmd spring-boot:run`

## Estratégia de validação

- Testes unitários para regras de compra, valores e transições de status.
- Testes de integração para restrições transacionais no PostgreSQL e endpoints REST.
- Testes de contrato para validar assinatura, consulta e processamento idempotente de Webhooks.
- Teste manual do fluxo de checkout com credenciais de teste do Mercado Pago antes de habilitar credenciais de produção.

## Limites de segurança

- Nunca confiar no preço, status de pagamento ou disponibilidade informados pelo frontend.
- Nunca marcar a compra como paga a partir da página de retorno.
- Validar a assinatura do Webhook e consultar o pagamento diretamente no Mercado Pago antes de confirmar a compra.
- Não registrar tokens, segredos de Webhook ou dados de cartão em logs.
- Validar entradas e limitar o que os endpoints públicos retornam.
