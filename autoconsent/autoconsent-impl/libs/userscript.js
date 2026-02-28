import AutoConsent from '@duckduckgo/autoconsent';
import { consentomatic } from '@duckduckgo/autoconsent/rules/consentomatic.json'

const autoconsent = new AutoConsent(
    (message) => {
        AutoconsentAndroid.process(JSON.stringify(message), window.autoconsentAndroidSecret || '');
    },
    null,
    {  consentomatic },
);
window.autoconsentMessageCallback = (msg) => {
    autoconsent.receiveMessageCallback(msg);
};