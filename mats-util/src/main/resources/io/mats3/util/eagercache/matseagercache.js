function matsecToggleAllLess(button) {
    const container = button.closest('.matsec-log-table-container');
    const isCollapsed = button.innerText === 'Show All';
    const rows = container.querySelectorAll('.matsec-log-row');
    rows.forEach((row, index) => {
        if (index >= 5) {
            row.classList.toggle('matsec-hidden', !isCollapsed);
        }
    });
    const throwables = container.querySelectorAll('.matsec-log-row-throwable');
    throwables.forEach((row, index) => {
        if (index >= 5) {
            row.classList.toggle('matsec-hidden', !isCollapsed);
        }
    });
    button.innerText = isCollapsed ? 'Show Less' : 'Show All';
}

function matsecFetchAndHandle(nearestElement, routingId, payload, successCallback, finallyCallback) {
    const url = typeof matsec_json_path !== 'undefined' ? matsec_json_path : window.location.href;
    const fullUrl = `${url}?routingId=${encodeURIComponent(routingId)}`;

    fetch(fullUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
    }).then(response => {
        if (response.ok) {
            return response.json();
        } else {
            console.error('Response not OK: ', response.statusText);
            throw new Error('Response not OK: ' + response.status + " " + response.statusText);
        }
    }).then(replyDto => {
        console.log('Server response:', replyDto.ok, replyDto.message);
        if (replyDto.ok) {
            successCallback();
            mastecShowSuccessMessage(replyDto.message, nearestElement);
        } else {
            console.error('Server said not ok: ', replyDto.message);
            matsecShowErrorLightbox('Error Reply from GUI', replyDto.message);
        }
    }).catch(error => {
        console.error('Error during fetch: ', error);
        matsecShowErrorLightbox('Network/Server error', error.message);
    }).finally(() => {
        if (finallyCallback) {
            finallyCallback();
        }
        console.log('Done.');
    });
}

function matsecAcknowledgeSingle(button, routingId, id) {
    const row = button.closest('.matsec-log-row');
    const cells = row.querySelectorAll('td');

    console.log("Acknowledge single: " + routingId + ", " + id);

    button.disabled = true;
    matsecFetchAndHandle(button, routingId, {op: 'acknowledgeSingle', id: id}, () => {
        cells.forEach(cell => {
            cell.classList.add('matsec-row-acknowledged');
            cell.classList.remove('matsec-row-unacknowledged');
        });
        button.disabled = true;
    });
}

function matsecAcknowledgeUpTo(button, routingId, timestamp) {
    console.log("Acknowledge Up To: " + routingId + ", " + timestamp);

    button.disabled = true;
    matsecFetchAndHandle(button, routingId, {op: 'acknowledgeUpTo', timestamp: timestamp}, () => {
        setTimeout(() => {
            location.reload();
        }, 2000);
    });
}

function matsecRequestRefresh(button, routingId, maxWaitSeconds) {
    console.log("Request refresh: " + routingId);

    const container = button.closest('.matsec-container'); // Ensure you are within the correct container
    const updatingMessageSpan = container.querySelector('.matsec-updating-message');
    console.log('Updating message span: ', updatingMessageSpan);
    const startTime = Date.now();
    let timer = setInterval(() => {
        const elapsedMilliseconds = Date.now() - startTime;
        const elapsedSeconds = (elapsedMilliseconds / 1000).toFixed(2);
        updatingMessageSpan.textContent = `Waiting for update up to ${maxWaitSeconds} seconds: ${elapsedSeconds}`;
    }, 25);

    button.disabled = true;
    matsecFetchAndHandle(button, routingId, {op: 'requestRefresh'},
        () => {
            setTimeout(() => {
                location.reload();
            }, 2500);
        }, () => {
            clearInterval(timer);
            const elapsedMilliseconds = Date.now() - startTime;
            const elapsedSeconds = (elapsedMilliseconds / 1000).toFixed(2);
            updatingMessageSpan.textContent = `Got update! Took: ${elapsedSeconds} seconds.`;
        });
}

function mastecShowSuccessMessage(message, nearElement) {
    const container = nearElement.closest('.matsec-container');
    const messageBubble = document.createElement('div');
    messageBubble.className = 'matsec-success-bubble';
    messageBubble.textContent = message;

    document.body.appendChild(messageBubble);

    let offsetFromTop = 70;

    function updateBubblePosition() {
        const containerRect = container.getBoundingClientRect();
        if (containerRect.top + offsetFromTop > offsetFromTop) { // The top can be negative if scrolled up!
            messageBubble.style.position = 'absolute';
            messageBubble.style.top = `${containerRect.top + window.scrollY + offsetFromTop}px`;
        } else {
            messageBubble.style.position = 'fixed';
            messageBubble.style.top = '50px';
        }
    }

    // Initial positioning, and update on scroll
    updateBubblePosition();
    const onScroll = () => {
        updateBubblePosition();
    };
    window.addEventListener('scroll', onScroll);

    setTimeout(() => {
        messageBubble.remove();
        window.removeEventListener('scroll', onScroll);
    }, 3000);
}

function matsecShowErrorLightbox(title, message) {
    const overlay = document.createElement('div');
    overlay.className = 'matsec-error-overlay';

    const lightbox = document.createElement('div');
    lightbox.className = 'matsec-error-lightbox';

    const lightboxTitle = document.createElement('h2');
    lightboxTitle.textContent = title;
    lightbox.appendChild(lightboxTitle);

    const lightboxMessage = document.createElement('p');
    lightboxMessage.textContent = message;
    lightbox.appendChild(lightboxMessage);

    const closeButton = document.createElement('button');
    closeButton.textContent = 'Reload page';
    closeButton.onclick = () => {
        location.reload();
    };
    lightbox.appendChild(closeButton);

    overlay.appendChild(lightbox);
    document.body.appendChild(overlay);
}
