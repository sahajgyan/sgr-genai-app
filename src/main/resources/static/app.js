// --- ENDPOINT CONFIGURATION ---
const API_PREFIX = '/backend-genai';
const DISCOVERY_ENDPOINT = `${API_PREFIX}/api/discovery/workflows`;
const STATUS_POLLING_INTERVAL = 2000; // Poll every 2 seconds
const LAST_WORKFLOW_KEY = 'lastSelectedWorkflowId'; // Key for localStorage

const statusMsgElement = document.getElementById('statusMessage');
// UPDATED: Use the new footer container ID
const fullResultContainer = document.getElementById('fullResultContainer');
const submitButton = document.getElementById('submitBtn');
const workflowIdSelect = document.getElementById('workflowIdSelect');
const postDataInput = document.getElementById('postDataInput');
const resultSummaryElement = document.getElementById('resultSummary');

const MAX_SUMMARY_VALUE_LENGTH = 100;


// --- Initialization ---

async function initializeUI() {
    // 1. Fetch and populate the workflow IDs
    await populateWorkflowDropdown();

    // 2. Attach the main submission listener
    submitButton.addEventListener('click', submitWorkflow);
}

document.addEventListener('DOMContentLoaded', initializeUI);


// --- Workflow ID Fetch and Populate (No Change) ---

async function populateWorkflowDropdown() {
    statusMsgElement.textContent = 'Fetching available workflows...';
    try {
        const response = await fetch(DISCOVERY_ENDPOINT);
        if (!response.ok) {
            throw new Error(`Discovery API failed: ${response.status} ${response.statusText}`);
        }

        const workflows = await response.json();

        workflowIdSelect.innerHTML = '';

        if (workflows.length === 0) {
            statusMsgElement.textContent = 'No workflows found.';
            return;
        }

        const lastSelectedId = localStorage.getItem(LAST_WORKFLOW_KEY);

        workflows.forEach(workflow => {
            const option = document.createElement('option');
            const workflowId = workflow.id;
            option.value = workflowId;
            option.textContent = workflowId;

            if (lastSelectedId === workflowId) {
                option.selected = true;
            }
            workflowIdSelect.appendChild(option);
        });

        workflowIdSelect.addEventListener('change', (event) => {
            localStorage.setItem(LAST_WORKFLOW_KEY, event.target.value);
        });

        statusMsgElement.textContent = 'Ready to submit workflow.';
        submitButton.disabled = false;

    } catch (error) {
        console.error('Failed to load workflows:', error);
        statusMsgElement.textContent = `Error loading workflows: ${error.message}. Ensure backend is running and the prefix ${API_PREFIX} is correct.`;
        submitButton.disabled = true;
    }
}

// --- Function to Format Result Summary (No Change) ---
function displayFormattedResult(data) {
    resultSummaryElement.innerHTML = '';

    let processedData = data;

    // FIX FOR STRING-WRAPPED JSON PAYLOAD (Second-level parsing)
    if (typeof processedData === 'string') {
        try {
            processedData = JSON.parse(processedData);
        } catch (e) {
            processedData = { content: processedData };
        }
    }

    if (typeof processedData !== 'object' || processedData === null) {
        resultSummaryElement.innerHTML = '<li style="padding: 10px; color: red;">Error: Result is not a JSON object.</li>';
        return;
    }

    const keys = Object.keys(processedData).slice(0, 5);

    keys.forEach(key => {
        let value = processedData[key];

        if (typeof value === 'object' && value !== null) {

            let jsonStringValue = JSON.stringify(value);

            if (jsonStringValue.length > MAX_SUMMARY_VALUE_LENGTH) {
                if (Array.isArray(value)) {
                    value = `[Array with ${value.length} items] (Too Long for Summary)`;
                } else {
                    value = `[JSON Object] (Too Long for Summary)`;
                }
            } else {
                value = jsonStringValue;
            }

        } else if (typeof value === 'string' && value.length > MAX_SUMMARY_VALUE_LENGTH) {
            value = value.substring(0, MAX_SUMMARY_VALUE_LENGTH) + '...';
        }

        const listItem = document.createElement('li');
        listItem.innerHTML = `<span class="key">${key}:</span> <span class="value">${value}</span>`;
        resultSummaryElement.appendChild(listItem);
    });

    if (Object.keys(processedData).length > keys.length) {
        const remaining = Object.keys(processedData).length - keys.length;
        const noteItem = document.createElement('li');
        noteItem.style.backgroundColor = '#dceefc';
        noteItem.innerHTML = `<span class="key">... and ${remaining} more keys</span>`;
        resultSummaryElement.appendChild(noteItem);
    }
}


// --- Polling Logic ---

async function pollStatus(jobId) {
    const statusEndpoint = `${API_PREFIX}/api/workflows/status/${jobId}`;

    statusMsgElement.textContent = `Polling server for results... Job ID: ${jobId}`;

    while (true) {
        try {
            const response = await fetch(statusEndpoint);

            if (!response.ok) {
                throw new Error(`Status check failed: ${response.status} ${response.statusText}`);
            }

            const statusData = await response.json();

            const finalResult = (typeof statusData.result === 'string')
                ? statusData.result
                : (statusData.result || statusData);

            const jobStatus = statusData.status;

            statusMsgElement.textContent = `Current Job Status: ${jobStatus}`;

            if (jobStatus === 'COMPLETED') {

                displayFormattedResult(finalResult);

                let rawOutput = (typeof finalResult === 'string') ? finalResult : JSON.stringify(finalResult, null, 2);

                // UPDATED: Use the new footer container ID
                fullResultContainer.textContent = rawOutput;
                statusMsgElement.textContent = 'Job COMPLETED. Final result displayed.';
                submitButton.disabled = false;
                break;
            }

            if (jobStatus === 'FAILED' || jobStatus === 'ERROR') {
                displayFormattedResult(statusData);

                // UPDATED: Use the new footer container ID
                fullResultContainer.textContent = JSON.stringify(statusData, null, 2);
                statusMsgElement.textContent = 'Job FAILED. See details above.';
                submitButton.disabled = false;
                break;
            }

            await new Promise(resolve => setTimeout(resolve, STATUS_POLLING_INTERVAL));

        } catch (error) {
            console.error('Polling error:', error);
            statusMsgElement.textContent = `Polling Error: ${error.message}`;
            submitButton.disabled = false;
            break;
        }
    }
}

// --- Submission Logic ---

async function submitWorkflow() {
    // 1. Reset UI and disable button
    resultSummaryElement.innerHTML = '';
    submitButton.disabled = true;
    statusMsgElement.textContent = 'Submitting workflow...';
    // UPDATED: Clear the new footer container
    fullResultContainer.textContent = 'The complete, raw JSON output will appear here after the job is complete.';

    // 2. Get inputs
    const workflowId = workflowIdSelect.value;
    const postDataString = postDataInput.value;

    if (!workflowId) {
        statusMsgElement.textContent = 'Error: Workflow ID is not selected.';
        submitButton.disabled = false;
        return;
    }

    try {
        const postData = JSON.parse(postDataString);
        const submitEndpoint = `${API_PREFIX}/api/workflows/submit/${workflowId}`;

        // 3. Initial POST Request
        const response = await fetch(submitEndpoint, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            },
            body: JSON.stringify(postData)
        });

        if (!response.ok) {
            const errorBody = await response.text();
            throw new Error(`Submission failed: ${response.status} ${response.statusText}. Details: ${errorBody}`);
        }

        const submissionResult = await response.json();
        const jobId = submissionResult.jobId;

        if (!jobId) {
            throw new Error("Server response missing 'jobId' for polling.");
        }

        statusMsgElement.textContent = `Submission successful. Received Job ID: ${jobId}`;

        // 4. Start Polling
        pollStatus(jobId);

    } catch (error) {
        console.error('Submission error:', error);
        statusMsgElement.textContent = `Submission Error: ${error.message}`;
        submitButton.disabled = false; // Re-enable button on error
    }
}