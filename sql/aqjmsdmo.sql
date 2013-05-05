Rem
Rem $Header: template.sql 06-feb-96.13:23:14 kosinski Exp $
Rem
Rem aqjmsdmo.sql
Rem
Rem  Copyright (c) Oracle Corporation 2000. All Rights Reserved.
Rem
Rem    NAME
Rem      aqjmsdmo.sql - <one-line expansion of the name>
Rem
Rem    DESCRIPTION
Rem      <short description of component this file declares/defines>
Rem
Rem    NOTES
Rem      <other useful comments, qualifications, etc.>
Rem
Rem    MODIFIED   (MM/DD/YY)
Rem    rbhyrava    07/10/00 - AQ JMS demo -setup
Rem    rbhyrava    07/10/00 - Created
Rem
REM =====================================================
REM SETUP for AQ JMS Demos:create user and payload types 
REM =====================================================
SET echo on;
CONNECT system/manager;
DROP USER jmsuser CASCADE ;
GRANT CONNECT, RESOURCE,AQ_ADMINISTRATOR_ROLE TO jmsuser IDENTIFIED BY jmsuser ;
CONNECT jmsuser/jmsuser;


EXIT;

REM ==============================================
REM SETUP complete 
REM ==============================================
