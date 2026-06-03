package com.example.ibmmq.report;

import com.example.ibmmq.model.ReportType;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Teste unitario (sem broker) do extrator {@link ReportDescriptor} — issue #19.
 *
 * <p>Cobre o invariante CRITICO de null-safety: quando o {@code mdReadEnabled} NAO esta ligado (ou o
 * teste usa um mock cru que so stuba feedback+correlationId), todo getter MQMD retorna null e a extracao
 * NAO pode lancar — caso contrario o caminho ja-ackado do relatorio abortaria. Tambem cobre o caminho
 * positivo (todos os seis valores presentes) e o fallback de {@code byte[]} chegando como hex String.</p>
 */
class ReportDescriptorTest {

    @Test
    @DisplayName("Mock cru (so feedback+correlId) nao lanca e deixa os campos MQMD nulos (null-safe)")
    void bareMockIsNullSafeAndDoesNotThrow() throws Exception {
        Message report = mock(Message.class);
        when(report.getJMSCorrelationID()).thenReturn("ID:abc");
        when(report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(259);
        // getStringProperty/getObjectProperty/getJMSCorrelationIDAsBytes NAO stubados -> retornam null.

        ReportDescriptor descriptor = extractWithoutThrowing(report, ReportType.COA);

        assertThat(descriptor.applIdentityData()).isNull();
        assertThat(descriptor.accountingToken()).isNull();
        assertThat(descriptor.correlationIdBytes()).isNull();
        assertThat(descriptor.messageIdBytes()).isNull();
        assertThat(descriptor.putTimestampUtc()).isNull();
        // O char derivado SEMPRE existe (vem do tipo, nao de uma propriedade MQMD).
        assertThat(descriptor.reportTypeChar()).isEqualTo('A');
        // Acessores hex toleram bytes nulos.
        assertThat(descriptor.accountingTokenHex()).isNull();
        assertThat(descriptor.correlationIdBytesHex()).isNull();
        assertThat(descriptor.messageIdBytesHex()).isNull();
    }

    @Test
    @DisplayName("Getter MQMD que lanca JMSException e tratado como ausente (sem propagar)")
    void throwingGetterIsSwallowed() throws Exception {
        Message report = mock(Message.class);
        when(report.getStringProperty(WMQConstants.JMS_IBM_MQMD_APPLIDENTITYDATA))
                .thenThrow(new JMSException("boom"));
        when(report.getObjectProperty(WMQConstants.JMS_IBM_MQMD_ACCOUNTINGTOKEN))
                .thenThrow(new JMSException("boom"));
        when(report.getJMSCorrelationIDAsBytes()).thenThrow(new JMSException("boom"));

        ReportDescriptor descriptor = extractWithoutThrowing(report, ReportType.COD);

        assertThat(descriptor.applIdentityData()).isNull();
        assertThat(descriptor.accountingToken()).isNull();
        assertThat(descriptor.correlationIdBytes()).isNull();
        assertThat(descriptor.reportTypeChar()).isEqualTo('D');
    }

    @Test
    @DisplayName("Caminho positivo: os seis valores sao recuperados do descriptor do relatorio")
    void recoversAllSixWhenPresent() throws Exception {
        byte[] accountingToken = new byte[]{0x01, 0x02, 0x03, (byte) 0xFF};
        byte[] correlIdBytes = new byte[]{0x10, 0x20};
        byte[] msgIdBytes = new byte[]{(byte) 0xAB, (byte) 0xCD};

        Message report = mock(Message.class);
        when(report.getStringProperty(WMQConstants.JMS_IBM_MQMD_APPLIDENTITYDATA)).thenReturn("APP-IDENTITY");
        when(report.getObjectProperty(WMQConstants.JMS_IBM_MQMD_ACCOUNTINGTOKEN)).thenReturn(accountingToken);
        when(report.getJMSCorrelationIDAsBytes()).thenReturn(correlIdBytes);
        when(report.getObjectProperty(WMQConstants.JMS_IBM_MQMD_MSGID)).thenReturn(msgIdBytes);
        when(report.getStringProperty(WMQConstants.JMS_IBM_MQMD_PUTDATE)).thenReturn("20260531");
        when(report.getStringProperty(WMQConstants.JMS_IBM_MQMD_PUTTIME)).thenReturn("13300050");

        ReportDescriptor descriptor = ReportDescriptor.from(report, ReportType.COA);

        assertThat(descriptor.applIdentityData()).isEqualTo("APP-IDENTITY");
        assertThat(descriptor.accountingToken()).containsExactly(accountingToken);
        assertThat(descriptor.accountingTokenHex()).isEqualTo("010203ff");
        assertThat(descriptor.correlationIdBytes()).containsExactly(correlIdBytes);
        assertThat(descriptor.correlationIdBytesHex()).isEqualTo("1020");
        assertThat(descriptor.messageIdBytes()).containsExactly(msgIdBytes);
        assertThat(descriptor.messageIdBytesHex()).isEqualTo("abcd");
        assertThat(descriptor.putTimestampUtc()).isEqualTo(LocalDateTime.parse("2026-05-31T13:30:00.500"));
        assertThat(descriptor.reportTypeChar()).isEqualTo('A');
    }

    @Test
    @DisplayName("Fallback defensivo: um byte[] MQMD chegando como String hex e decodificado")
    void hexStringFallbackForBytesProperty() throws Exception {
        Message report = mock(Message.class);
        // AccountingToken chega como String hex em vez de byte[] (forma defensiva).
        when(report.getObjectProperty(WMQConstants.JMS_IBM_MQMD_ACCOUNTINGTOKEN)).thenReturn("0a0b0c");

        ReportDescriptor descriptor = ReportDescriptor.from(report, ReportType.COD);

        assertThat(descriptor.accountingToken()).containsExactly(0x0a, 0x0b, 0x0c);
        assertThat(descriptor.accountingTokenHex()).isEqualTo("0a0b0c");
    }

    /** Constroi o descriptor exigindo que a extracao NAO lance (o invariante do caminho ja-ackado). */
    private static ReportDescriptor extractWithoutThrowing(Message report, ReportType type) {
        ReportDescriptor[] holder = new ReportDescriptor[1];
        assertThatCode(() -> holder[0] = ReportDescriptor.from(report, type))
                .as("a extracao MQMD nunca lanca (caminho ja-ackado do relatorio)")
                .doesNotThrowAnyException();
        return holder[0];
    }
}
