# IBM MQ 9.4 COA/COD Report Constants — Validation Findings

Verified against com.ibm.mq.allclient-9.4.5.0.jar bytecode (Maven Central, published 2026-01-30)
and IBM Knowledge Center text (via setgetweb.com mirror; ibm.com returned 403 to WebFetch).

## Fact 1 — JMS report property fields
Java FIELD names (in com.ibm.msg.client.jms.JmsConstants, inherited by WMQConstants) are UPPERCASE.
Each holds a mixed-case STRING value = the JMS message property name.

| Java field (JmsConstants/WMQConstants) | String value (JMS property name) |
|---|---|
| JMS_IBM_REPORT_COA | "JMS_IBM_Report_COA" |
| JMS_IBM_REPORT_COD | "JMS_IBM_Report_COD" |
| JMS_IBM_REPORT_EXCEPTION | "JMS_IBM_Report_Exception" |
| JMS_IBM_REPORT_EXPIRATION | "JMS_IBM_Report_Expiration" |
| JMS_IBM_REPORT_PAN | "JMS_IBM_Report_PAN" |
| JMS_IBM_REPORT_NAN | "JMS_IBM_Report_NAN" |
| JMS_IBM_REPORT_PASS_MSG_ID | "JMS_IBM_Report_Pass_Msg_ID" |
| JMS_IBM_REPORT_PASS_CORREL_ID | "JMS_IBM_Report_Pass_Correl_ID" |
| JMS_IBM_REPORT_DISCARD_MSG | "JMS_IBM_Report_Discard_Msg" |

## Fact 2 — MQRO_* int constants (declared in com.ibm.mq.constants.CMQC, NOT WMQConstants)
MQRO_COA=256, MQRO_COA_WITH_DATA=768, MQRO_COA_WITH_FULL_DATA=1792
MQRO_COD=2048, MQRO_COD_WITH_DATA=6144, MQRO_COD_WITH_FULL_DATA=14336
MQRO_EXCEPTION=16777216, _WITH_DATA=50331648, _WITH_FULL_DATA=117440512
MQRO_EXPIRATION=2097152, _WITH_DATA=6291456, _WITH_FULL_DATA=14680064
MQRO_PAN=1, MQRO_NAN=2
MQRO_COPY_MSG_ID_TO_CORREL_ID=0, MQRO_PASS_MSG_ID=128, MQRO_PASS_CORREL_ID=64
MQRO_NEW_MSG_ID=0, MQRO_DISCARD_MSG=134217728, MQRO_DEAD_LETTER_Q=0, MQRO_NONE=0

## Fact 3 — Feedback codes (CMQC)
MQFB_EXPIRATION=258, MQFB_COA=259, MQFB_COD=260, MQFB_PAN=275, MQFB_NAN=276
MQFB_APPL_FIRST=65536, MQFB_APPL_LAST=999999999, MQFB_SYSTEM_FIRST=1, MQFB_SYSTEM_LAST=65535
Exception report Feedback = an IBM MQ reason code (MQRC_*), e.g. MQRC_PUT_INHIBITED, MQRC_Q_FULL.

## Fact 4 — default id propagation
MQRO_COPY_MSG_ID_TO_CORREL_ID (value 0) is the DEFAULT ACTION: MsgId of original copied to CorrelId of report.

## Fact 5 — timing
COA generated when message placed on destination queue; COD when an app does a destructive get.
Under syncpoint: COA report retrievable only after the unit of work is committed.

## Fact 6 — persistence (REFUTED claim)
Report message Persistence = "Copied from the original message descriptor" — report INHERITS the
original's persistence. A report is NOT non-persistent by default when original is persistent.
