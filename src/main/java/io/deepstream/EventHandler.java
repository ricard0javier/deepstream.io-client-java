package io.deepstream;

import io.deepstream.constants.Actions;
import io.deepstream.constants.Event;
import io.deepstream.constants.Topic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class EventHandler implements UtilResubscribeCallback {

    private UtilEmitter emitter;
    private Map options;
    private IConnection connection;
    private DeepstreamClient client;
    private UtilAckTimeoutRegistry ackTimeoutRegistry;
    private UtilResubscribeNotifier resubscribeNotifier;
    private Map<String, UtilListener> listeners;
    private List<String> subscriptions;

    public EventHandler(Map options, IConnection connection, DeepstreamClient client ) {
        this.emitter = new UtilEmitter();
        this.connection = connection;
        this.client = client;
        this.options = options;
        this.listeners = new HashMap<>();
        this.subscriptions = new ArrayList<>();
        int subscriptionTimeout = Integer.parseInt( (String) options.get( "subscriptionTimeout" ) );
        this.ackTimeoutRegistry = new UtilAckTimeoutRegistry( client, Topic.EVENT, subscriptionTimeout );
        this.resubscribeNotifier = new UtilResubscribeNotifier( this.client, this );
    }

    public void subscribe( String eventName, UtilEmitter.Listener eventListener ) {
        if( this.emitter.hasListeners( eventName ) == false ) {
            this.subscriptions.add( eventName );
            this.ackTimeoutRegistry.add( eventName, Actions.SUBSCRIBE );
            this.connection.send( MessageBuilder.getMsg( Topic.EVENT, Actions.SUBSCRIBE, eventName ) );
        }
        this.emitter.on( eventName, eventListener );
    }

    public void unsubscribe( String eventName, UtilEmitter.Listener eventListener ) {
        this.subscriptions.remove( eventName );
        this.emitter.off(eventName, eventListener);
        if ( this.emitter.hasListeners(eventName) == false ) {
            this.ackTimeoutRegistry.add( eventName, Actions.UNSUBSCRIBE );
            this.connection.send(MessageBuilder.getMsg(Topic.EVENT, Actions.UNSUBSCRIBE, eventName));
        }
    }

    public void emit( String eventName ) {
        this.connection.send( MessageBuilder.getMsg( Topic.EVENT, Actions.EVENT, eventName ) );
        this.emitter.emit( eventName );
    }

    public void emit( String eventName, Object data ) {
        this.connection.send( MessageBuilder.getMsg( Topic.EVENT, Actions.EVENT, eventName, MessageBuilder.typed( data ) ) );
        this.emitter.emit( eventName, data );
    }

    public void listen( String pattern, UtilEmitter.Listener callback ) {
        if( this.listeners.get( pattern ) != null ) {
            this.client.onError( Topic.EVENT, Event.LISTENER_EXISTS, pattern );
        } else {
            this.listeners.put( pattern,
                    new UtilListener(Topic.EVENT, pattern, callback, this.options, this.client, this.connection )
            );
        }
    }

    public void unlisten( String pattern ) {
        UtilListener listener = this.listeners.get( pattern );
        if( listener != null ) {
            this.ackTimeoutRegistry.add( pattern, Actions.UNLISTEN );
            listener.destroy();
            this.listeners.remove( pattern );
        } else {
            this.client.onError( Topic.EVENT, Event.NOT_LISTENING, pattern );
        }
    }

    public void handle( Message message ) {
        String eventName;

        if( message.action == Actions.ACK ) {
            eventName = message.data[ 1 ];
        } else {
            eventName = message.data[ 0 ];
        }

        if( message.action == Actions.EVENT ) {
            if( message.data.length == 2 ) {
                this.emitter.emit( eventName, MessageParser.convertTyped( message.data[ 1 ], this.client ) );
            } else {
                this.emitter.emit( eventName );
            }
        }
        else if( this.listeners.get( eventName ) != null ) {
            this.listeners.get( eventName ).onMessage( message );
        }
        else if( message.action == Actions.ACK ) {
            this.ackTimeoutRegistry.clear( message );
        }
        else if( message.action == Actions.ERROR ) {
            this.client.onError( Topic.EVENT, Event.getEvent( message.data[ 0 ] ), message.data[ 1 ]);
        }
        else {
            this.client.onError( Topic.EVENT, Event.UNSOLICITED_MESSAGE, eventName );
        }
    }

    @Override
    public void resubscribe() {
        for ( String eventName : this.subscriptions ) {
            this.connection.sendMsg( Topic.EVENT, Actions.SUBSCRIBE, new String[] { eventName } );
        }
    }
}