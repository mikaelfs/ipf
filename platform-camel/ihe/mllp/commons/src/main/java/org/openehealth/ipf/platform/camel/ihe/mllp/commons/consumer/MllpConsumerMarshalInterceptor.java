/*
 * Copyright 2009 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openehealth.ipf.platform.camel.ihe.mllp.commons.consumer;

import org.apache.camel.CamelException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openehealth.ipf.modules.hl7.AbstractHL7v2Exception;
import org.openehealth.ipf.modules.hl7.AckTypeCode;
import org.openehealth.ipf.modules.hl7.HL7v2Exception;
import org.openehealth.ipf.modules.hl7.message.MessageUtils;
import org.openehealth.ipf.modules.hl7dsl.MessageAdapter;
import org.openehealth.ipf.platform.camel.ihe.mllp.commons.MllpComponent;
import org.openehealth.ipf.platform.camel.ihe.mllp.commons.MllpEndpoint;
import org.openehealth.ipf.platform.camel.ihe.mllp.commons.MllpEndpointConfiguration;
import org.openehealth.ipf.platform.camel.ihe.mllp.commons.MllpMarshalUtils;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;


/**
 * Consumer-side HL7 marshaling/unmarshaling Camel interceptor.
 * 
 * @author Dmytro Rud
 */
public class MllpConsumerMarshalInterceptor extends AbstractMllpConsumerInterceptor {
    private static final transient Log LOG = LogFactory.getLog(MllpConsumerMarshalInterceptor.class);

    public MllpConsumerMarshalInterceptor(MllpEndpoint endpoint, Processor wrappedProcessor) {
        super(endpoint, wrappedProcessor);
    }


    /**
     * Unmarshals the request, passes it to the processing route, 
     * and marshals the response.
     */
    public void process(Exchange exchange) throws Exception {
        MessageAdapter originalAdapter = null;
        Message originalMessage = null;
        
        // unmarshal
        boolean unmarshallingFailed = false;
        try {
            MllpMarshalUtils.unmarshal(exchange, getMllpEndpoint().getCharsetName()); 

            // save a copy of the request message 
            originalAdapter = exchange.getIn().getBody(MessageAdapter.class).copy();
            originalMessage = (Message) originalAdapter.getTarget();
        } catch (Exception e) {
            unmarshallingFailed = true;
            LOG.error("Unmarshalling failed, message processing not possible", e);
            processUnmarshallingException(exchange, e);
        }
        
        // run the route (with implicit acceptance check in the next interceptor)
        if( ! unmarshallingFailed) {
            try {
                getWrappedProcessor().process(exchange);
                checkExchangeFailed(exchange, originalMessage);
            } catch(Exception e) {
                LOG.error("Message processing failed", e);
                exchange.getIn().setBody(createNak(e, originalMessage));
            }
        }

        // marshal the response (or the NAK)
        String s = marshal(exchange, originalMessage);
        if(s == null) {
            throw new CamelException("Do not know how to handle results of PIX/PDQ route execution");
        }
        exchange.getOut().setBody(s);
    }
    
    
    /**
     * Checks whether the given exchange has failed.  
     * If yes, substitute the exception object with a HL7 NAK  
     * and mark the exchange as successful. 
     */
    private void checkExchangeFailed(Exchange exchange, Message original) throws Exception {
        if (exchange.isFailed()) {
            Throwable t; 
            if(exchange.getException() != null) {
                t = exchange.getException();
                exchange.setException(null);
            } else {
                t = (Throwable) exchange.getFault().getBody();
                exchange.getFault().setBody(null);
            }
            LOG.error("Message processing failed", t);
            exchange.getIn().setBody(createNak(t, original));
        }
    }
    
    
    /**
     * Generates a default NAK message on unmarshalling errors 
     * and stores it into the exchange.
     */
    private void processUnmarshallingException(Exchange exchange, Throwable t) {
        MllpEndpointConfiguration config = getMllpEndpoint().getEndpointConfiguration();
        
        HL7v2Exception hl7e = new HL7v2Exception(
                t.getMessage(),
                config.getRequestErrorDefaultErrorCode(), 
                t);
        
        Object nak = MessageUtils.defaultNak(
                hl7e, 
                config.getRequestErrorDefaultAckTypeCode(), 
                config.getHl7Version(),
                config.getSendingApplication(),
                config.getSendingFacility());
        
        exchange.getIn().setBody(nak);
    }
    

    /**
     * Generates a NAK message on processing errors on the basis   
     * of the original request message. 
     */
    private Message createNak(Throwable t, Message original) throws Exception {
        MllpEndpointConfiguration config = getMllpEndpoint().getEndpointConfiguration();
        
        AbstractHL7v2Exception hl7Exception;
        if(t instanceof AbstractHL7v2Exception) {
            hl7Exception = (AbstractHL7v2Exception) t; 
        } else if(t.getCause() instanceof AbstractHL7v2Exception) {
            hl7Exception = (AbstractHL7v2Exception) t.getCause();
        } else {
            hl7Exception = new HL7v2Exception(
                    t.getMessage(), 
                    config.getRequestErrorDefaultErrorCode(), 
                    t); 
        }

        return (Message) MessageUtils.nak(
                original, 
                hl7Exception, 
                config.getRequestErrorDefaultAckTypeCode());
    }
    

    /**
     * Marshals the contents of the exchange.
     */
    private String marshal(Exchange exchange, Message original) throws Exception {
        // try standard data types first
        String s = MllpMarshalUtils.marshalStandardTypes(
                exchange, 
                getMllpEndpoint().getCharsetName());
        
        // additionally: an Exception in the body?
        if(s == null) {
            Object body = exchange.getIn().getBody();
            if(body instanceof Exception) {
                Message nak = createNak((Exception) body, original);
                s = new PipeParser().encode(nak);
            }
        }
        
        // no known data type --> determine user's intention on the basis of a header 
        if((s == null) && (original != null)) {
            String header = (String) exchange.getIn().getHeader(MllpComponent.ACK_TYPE_CODE_HEADER);
            if("AA".equals(header)) {
                Message ack = (Message) MessageUtils.ack(original); 
                s = new PipeParser().encode(ack);
            } else if("AE".equals(header) || "AR".equals(header)) {
                HL7v2Exception exception = new HL7v2Exception(
                        "Error in PIX/PDQ route, output type not supported", 
                        getMllpEndpoint().getEndpointConfiguration().getResponseErrorDefaultErrorCode());
                Message nak = (Message) MessageUtils.nak(
                        original,
                        exception,
                        AckTypeCode.valueOf(header));
                s = new PipeParser().encode(nak);
            }
        }

        // return what we can return
        return s;
    }
}