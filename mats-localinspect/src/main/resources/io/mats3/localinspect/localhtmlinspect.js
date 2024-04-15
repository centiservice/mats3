function matsli_messages_summary(event) {
    let button = event.target;
    let parentSpan = button.closest("span")
    parentSpan.querySelector(".matsli_msgs_details").classList.add("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_details_btn").classList.remove("matsli_msgs_summary_or_details_btn_active");
    parentSpan.querySelector(".matsli_msgs_summary").classList.remove("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_summary_btn").classList.add("matsli_msgs_summary_or_details_btn_active");

}

function matsli_messages_details(event) {
    let button = event.target;
    let parentSpan = button.closest("span")
    parentSpan.querySelector(".matsli_msgs_details").classList.remove("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_details_btn").classList.add("matsli_msgs_summary_or_details_btn_active");
    parentSpan.querySelector(".matsli_msgs_summary").classList.add("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_summary_btn").classList.remove("matsli_msgs_summary_or_details_btn_active");
}

function matsli_systeminformation_toggle_height(event) {
    console.log(event);
    let button = event.target;
    let parentDiv = button.closest(".matsli_system_information");
    console.log(parentDiv)
    let contentBox = parentDiv.querySelector(".matsli_system_information_content");
    if (contentBox.style.maxHeight === "none") {
        contentBox.style.maxHeight = "22.5em";
        button.textContent = "Expand to full";
    } else {
        contentBox.style.maxHeight = "none";
        button.textContent = "Contract to small";
    }
}

// ::: CODE TO BE RUN AFTER DOMContentLoaded and on resize :::

function matsli_debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

const matsli_debounced_reposition_tooltip = matsli_debounce(() => {
    document.querySelectorAll('.matsli_tooltip').forEach(matsli_reposition_tooltip);
}, 100);

document.addEventListener('DOMContentLoaded', matsli_debounced_reposition_tooltip);
window.addEventListener('resize', matsli_debounced_reposition_tooltip);

function matsli_reposition_tooltip(tooltip) {
    let tooltip_Rect = tooltip.getBoundingClientRect();
    let tooltiptext = tooltip.querySelector('.matsli_tooltiptext');
    let tooltiptext_Rect = tooltiptext.getBoundingClientRect();

    // How much space to the left and right of the hover element
    let spaceLeft = tooltip_Rect.left;
    let spaceRight = window.innerWidth - tooltip_Rect.right;
    let tooltiptext_Width = tooltiptext_Rect.width;

    const offset = 18;

    let tooltipLeft = 'auto'
    let tooltipRight = 'auto';
    let caretLeft = '50%';

    // Reposition tooltip and caret based on available space
    if ((spaceRight - offset) < tooltiptext_Width / 2) {
        // Not enough space on the right, so we use as much space as possible on the right
        tooltipRight = (-spaceRight) + offset + 'px';
        caretLeft = tooltiptext_Width - spaceRight + (tooltip_Rect.width / 2) + "px";
    } else if (spaceLeft < tooltiptext_Width / 2) {
        // Not enough space on the left, so we use as much space as possible on the left
        tooltipLeft = (-spaceLeft) + offset + 'px';
        caretLeft = spaceLeft - offset + (tooltip_Rect.width / 2) + "px";
    } else {
        // Enough space on both sides, so we center it - relative to the hover element
        tooltipLeft = -(tooltiptext_Width / 2) + (tooltip_Rect.width / 2) + 'px';
        caretLeft = '50%';
    }
    tooltiptext.style.left = tooltipLeft;
    tooltiptext.style.right = tooltipRight;
    tooltiptext.style.setProperty('--caret-left', caretLeft);
}