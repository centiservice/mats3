function matsecToggleLogs(button) {
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