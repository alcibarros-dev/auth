-- Contas locais: uma por "sub" de token, criada no primeiro acesso (módulo contas).
CREATE TABLE contas (
    id        bigserial PRIMARY KEY,
    subject   text        NOT NULL UNIQUE,
    email     text,
    criada_em timestamptz NOT NULL DEFAULT now()
);

-- Notas de cada conta (módulo notas). O servidor de autorização usa o schema "auth" do mesmo banco.
CREATE TABLE notas (
    id        bigserial PRIMARY KEY,
    conta_id  bigint        NOT NULL REFERENCES contas (id),
    titulo    varchar(120)  NOT NULL,
    texto     varchar(4000) NOT NULL,
    criada_em timestamptz   NOT NULL
);

CREATE INDEX notas_conta_id ON notas (conta_id);
