package com.example.ibmmq.report;

import com.example.ibmmq.model.ReportType;
import com.ibm.mq.constants.MQConstants;
import jakarta.inject.Singleton;

/**
 * Roteia o codigo de feedback do MQMD (MQMD.Feedback / propriedade JMS {@code JMS_IBM_Feedback})
 * para um {@link ReportType}.
 *
 * <p><b>Por que uma classe pura?</b> Esta logica nao depende de JMS nem do broker — recebe um
 * {@code int} e devolve um enum. Isso permite testes unitarios deterministicos (sem container MQ)
 * que validam o mapeamento contra os valores inteiros exatos dos constants {@code MQFB_*}.</p>
 *
 * <p>Valores (verificados no bytecode de {@code com.ibm.mq.constants.CMQC} 9.4.5.0):
 * {@code MQFB_COA=259}, {@code MQFB_COD=260}, {@code MQFB_EXPIRATION=258},
 * {@code MQFB_PAN=275}, {@code MQFB_NAN=276}.</p>
 *
 * <p><b>Relatorios de excecao:</b> nao existe constante {@code MQFB_EXCEPTION}. Um relatorio de
 * excecao carrega no campo Feedback um <em>reason code</em> {@code MQRC_*} (ex. {@code MQRC_Q_FULL},
 * {@code MQRC_PUT_INHIBITED}, {@code MQRC_NOT_AUTHORIZED}). Por isso, qualquer feedback que nao seja
 * um dos MQFB_* de notificacao conhecidos e tratado como {@link ReportType#EXCEPTION} quando cai na
 * faixa de reason codes do sistema, ou {@link ReportType#UNKNOWN} caso contrario.</p>
 */
@Singleton
public class ReportFeedbackRouter {

    /**
     * Classifica um codigo de feedback.
     *
     * @param feedbackCode valor de {@code MQMD.Feedback} (lido em JMS via {@code JMS_IBM_Feedback}).
     * @return o {@link ReportType} correspondente.
     */
    public ReportType classify(int feedbackCode) {
        // Notificacoes geradas pelo gerenciador de filas / pela app consumidora.
        if (feedbackCode == MQConstants.MQFB_COA) {
            return ReportType.COA;
        }
        if (feedbackCode == MQConstants.MQFB_COD) {
            return ReportType.COD;
        }
        if (feedbackCode == MQConstants.MQFB_EXPIRATION) {
            return ReportType.EXPIRATION;
        }
        if (feedbackCode == MQConstants.MQFB_PAN) {
            return ReportType.PAN;
        }
        if (feedbackCode == MQConstants.MQFB_NAN) {
            return ReportType.NAN;
        }

        // MQFB_NONE (=0) nao indica relatorio: trata-se como desconhecido.
        if (feedbackCode == MQConstants.MQFB_NONE) {
            return ReportType.UNKNOWN;
        }

        // Faixa de reason codes do sistema (MQFB_SYSTEM_FIRST..MQFB_SYSTEM_LAST = 1..65535):
        // relatorios de excecao carregam um MQRC_* aqui. Excluindo os MQFB_* ja tratados acima,
        // qualquer outro valor nesta faixa e um relatorio de excecao.
        if (feedbackCode >= MQConstants.MQFB_SYSTEM_FIRST
                && feedbackCode <= MQConstants.MQFB_SYSTEM_LAST) {
            return ReportType.EXCEPTION;
        }

        // Fora das faixas conhecidas (ex. faixa de aplicacao MQFB_APPL_FIRST..) — desconhecido.
        return ReportType.UNKNOWN;
    }
}
